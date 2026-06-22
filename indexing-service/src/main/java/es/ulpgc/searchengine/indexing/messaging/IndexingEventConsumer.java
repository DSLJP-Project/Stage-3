package es.ulpgc.searchengine.indexing.messaging;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import es.ulpgc.searchengine.indexing.MetadataExtractor;
import es.ulpgc.searchengine.indexing.hazelcast.HazelcastIndex;
import es.ulpgc.searchengine.indexing.repository.DatamartMongo;
import org.apache.activemq.ActiveMQConnectionFactory;

import javax.jms.Connection;
import javax.jms.DeliveryMode;
import javax.jms.MessageConsumer;
import javax.jms.MessageProducer;
import javax.jms.Session;
import javax.jms.TextMessage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Consumes durable ingestion events. The indexer reads data through the
 * datalake HTTP API, trying every replica URL, so it never relies on a local
 * mount belonging to a different physical PC.
 */
public class IndexingEventConsumer implements AutoCloseable {

    private Connection connection;
    private Session session;
    private MessageConsumer consumer;
    private MessageProducer indexedProducer;
    private final Gson gson = new Gson();
    private final DatamartMongo repo;
    private final HazelcastIndex hz;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private boolean ready;

    public IndexingEventConsumer(DatamartMongo repo, HazelcastIndex hz) {
        this.repo = repo;
        this.hz = hz;
        try {
            String brokerUrl = System.getenv().getOrDefault("BROKER_URL", "tcp://activemq:61616");
            String ingestionQueue = System.getenv().getOrDefault("INGESTION_QUEUE", "document.ingested");
            String indexedQueue = System.getenv().getOrDefault("INDEXED_QUEUE", "book-indexed");

            ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(brokerUrl);
            connection = factory.createConnection();
            connection.start();
            // Commit only after Mongo, Hazelcast and the indexed event succeed.
            session = connection.createSession(true, Session.SESSION_TRANSACTED);
            consumer = session.createConsumer(session.createQueue(ingestionQueue));
            indexedProducer = session.createProducer(session.createQueue(indexedQueue));
            indexedProducer.setDeliveryMode(DeliveryMode.PERSISTENT);

            consumer.setMessageListener(message -> consume(message));
            ready = true;
            System.out.println("[IndexingConsumer] Listening on " + ingestionQueue);
        } catch (Exception e) {
            System.err.println("[IndexingConsumer] Startup failed: " + e.getMessage());
            close();
        }
    }

    private void consume(javax.jms.Message message) {
        try {
            if (!(message instanceof TextMessage textMessage)) {
                session.commit();
                return;
            }
            JsonObject event = JsonParser.parseString(textMessage.getText()).getAsJsonObject();
            int bookId = event.get("book_id").getAsInt();
            StoredDocument document = fetchDocument(event, bookId);
            if (document == null || document.content().isBlank()) {
                throw new IllegalStateException("No reachable datalake replica for book " + bookId);
            }

            String contentHash = sha256(document.content());
            MetadataExtractor.Meta meta = MetadataExtractor.extractText(document.header());
            repo.deleteIndexForBook(bookId);
            repo.insertOrUpdateBook(bookId, meta.title, meta.author, meta.language, meta.year,
                    document.content(), contentHash);

            // Safe for redelivery: MultiMap is SET-backed and deleteBook removes stale terms.
            hz.deleteBook(bookId);
            hz.indexBook(Map.of("book_id", bookId, "content", document.content()));
            publishDocumentIndexed(bookId, contentHash);
            session.commit();
            System.out.println("[IndexingConsumer] Indexed book " + bookId + " from a replicated datalake node");
        } catch (Exception e) {
            System.err.println("[IndexingConsumer] Processing failed; message will be redelivered: " + e.getMessage());
            try { session.rollback(); } catch (Exception rollbackError) {
                System.err.println("[IndexingConsumer] Rollback failed: " + rollbackError.getMessage());
            }
        }
    }

    private StoredDocument fetchDocument(JsonObject event, int bookId) {
        List<String> sources = new ArrayList<>();
        if (event.has("source_urls") && event.get("source_urls").isJsonArray()) {
            JsonArray array = event.getAsJsonArray("source_urls");
            array.forEach(value -> sources.add(value.getAsString()));
        }
        for (String source : sources) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(stripSlash(source) + "/documents/" + bookId))
                        .timeout(Duration.ofSeconds(20)).GET().build();
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) continue;
                JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
                return new StoredDocument(body.get("header").getAsString(), body.get("content").getAsString());
            } catch (Exception e) {
                System.err.println("[IndexingConsumer] Replica unavailable for book " + bookId + ": " + source);
            }
        }

        // Compatibility path for a one-machine Compose run of the old project.
        if (event.has("path")) {
            try {
                Path directory = Paths.get(event.get("path").getAsString());
                Path content = directory.resolve("content.txt");
                Path header = directory.resolve("header.txt");
                if (Files.exists(content)) return new StoredDocument(
                        Files.exists(header) ? Files.readString(header) : "", Files.readString(content));
            } catch (Exception ignored) { }
        }
        return null;
    }

    private void publishDocumentIndexed(int bookId, String contentHash) throws Exception {
        JsonObject event = new JsonObject();
        event.addProperty("eventType", "document.indexed");
        event.addProperty("book_id", bookId);
        event.addProperty("content_hash", contentHash);
        indexedProducer.send(session.createTextMessage(gson.toJson(event)));
    }

    private static String stripSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String sha256(String text) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(bytes);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot hash content", e);
        }
    }

    public boolean isReady() { return ready; }

    @Override
    public void close() {
        try { if (consumer != null) consumer.close(); } catch (Exception ignored) { }
        try { if (indexedProducer != null) indexedProducer.close(); } catch (Exception ignored) { }
        try { if (session != null) session.close(); } catch (Exception ignored) { }
        try { if (connection != null) connection.close(); } catch (Exception ignored) { }
        consumer = null;
        indexedProducer = null;
        session = null;
        connection = null;
        ready = false;
    }

    private record StoredDocument(String header, String content) { }
}
