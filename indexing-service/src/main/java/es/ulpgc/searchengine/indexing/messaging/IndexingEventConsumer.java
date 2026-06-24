package es.ulpgc.searchengine.indexing.messaging;

import javax.jms.*;
import org.apache.activemq.ActiveMQConnectionFactory;
import es.ulpgc.searchengine.indexing.repository.DatamartMongo;
import es.ulpgc.searchengine.indexing.hazelcast.HazelcastIndex;
import es.ulpgc.searchengine.indexing.MetadataExtractor;
import es.ulpgc.searchengine.indexing.MetadataExtractor.Meta;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Gson;

public class IndexingEventConsumer implements AutoCloseable {

    private Connection connection;
    private Session session;
    private MessageConsumer consumer;
    private MessageProducer indexedProducer;
    private final Gson gson = new Gson();
    private boolean ready = false;

    public IndexingEventConsumer(DatamartMongo repo, HazelcastIndex hz) {
        try {
            String brokerUrl = System.getenv().getOrDefault("BROKER_URL", "tcp://activemq:61616");
            String ingestionQueueName = System.getenv().getOrDefault("INGESTION_QUEUE", "document.ingested");
            String indexedQueueName = System.getenv().getOrDefault("INDEXED_QUEUE", "book-indexed");

            ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(brokerUrl);
            connection = factory.createConnection();
            connection.start();
            session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);

            Destination ingestionQueue = session.createQueue(ingestionQueueName);
            consumer = session.createConsumer(ingestionQueue);

            Destination indexedQueue = session.createQueue(indexedQueueName);
            indexedProducer = session.createProducer(indexedQueue);
            indexedProducer.setDeliveryMode(DeliveryMode.PERSISTENT);

            consumer.setMessageListener(msg -> {
                try {
                    if (!(msg instanceof TextMessage tm)) return;

                    String json = tm.getText();
                    JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
                    int bookId = obj.has("book_id") ? obj.get("book_id").getAsInt() : obj.get("bookId").getAsInt();
                    String path = obj.has("path") ? obj.get("path").getAsString() : null;

                    System.out.println("[IndexingConsumer] Procesando libro " + bookId + " en " + path);

                    String content = readContent(path, bookId);
                    if (content == null || content.isBlank()) {
                        System.err.println("[IndexingConsumer] Contenido vacío o nulo para libro " + bookId);
                        return;
                    }

                    // Extracción de metadatos
                    String title = "Unknown Title", author = "Unknown Author", lang = "en";
                    int year = 0;
                    if (path != null) {
                        Path headerFile = Paths.get(path).resolve("header.txt");
                        if (Files.exists(headerFile)) {
                            MetadataExtractor.Meta meta = MetadataExtractor.extract(headerFile);
                            title = meta.title; author = meta.author; lang = meta.language; year = meta.year;
                        }
                    }

                    // --- BLINDAJE: MongoDB ---
                    try {
                        repo.deleteIndexForBook(bookId);
                        repo.insertOrUpdateBook(bookId, title, author, lang, year, content);
                        System.out.println("[IndexingConsumer] Persistido en MongoDB: " + bookId);
                    } catch (Exception e) {
                        System.err.println("[IndexingConsumer] Error grave en Mongo: " + e.getMessage());
                    }

                    // --- BLINDAJE: Hazelcast ---
                    try {
                        hz.deleteBook(bookId);
                        Map<String, Object> bookForIndex = new HashMap<>();
                        bookForIndex.put("book_id", bookId);
                        bookForIndex.put("content", content);

                        // Añadimos un log ANTES de la indexación
                        System.out.println("[IndexingConsumer] Iniciando indexación para libro " + bookId);
                        hz.indexBook(bookForIndex);
                        System.out.println("[IndexingConsumer] Indexado en Hazelcast: " + bookId);
                    } catch (Exception e) {
                        // ESTO ES LO IMPORTANTE: Imprimir el stack trace completo
                        System.err.println("[IndexingConsumer] ERROR GRAVE EN HAZELCAST para libro " + bookId + ":");
                        e.printStackTrace();
                    }

                    publishDocumentIndexed(bookId, sha256(content));

                } catch (Exception e) {
                    System.err.println("[IndexingConsumer] Error general: " + e.getMessage());
                }
            });

            ready = true;
            System.out.println("[IndexingConsumer] Escuchando mensajes correctamente.");
        } catch (Exception e) {
            System.err.println("[IndexingConsumer] Error al iniciar: " + e.getMessage());
            close();
        }
    }

    private String readContent(String path, int bookId) {
        if (path == null) return null;
        try {
            Path dir = Paths.get(path);
            Path contentFile = dir.resolve("content.txt");
            if (Files.exists(contentFile)) return Files.readString(contentFile);
            return null;
        } catch (Exception e) { return null; }
    }

    private void publishDocumentIndexed(int bookId, String contentHash) {
        try {
            JsonObject evt = new JsonObject();
            evt.addProperty("eventType", "document.indexed");
            evt.addProperty("book_id", bookId);
            evt.addProperty("content_hash", contentHash);
            TextMessage msg = session.createTextMessage(gson.toJson(evt));
            indexedProducer.send(msg);
        } catch (Exception e) { System.err.println("Error enviando evento: " + e.getMessage()); }
    }

    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { return ""; }
    }

    public boolean isReady() { return ready; }

    @Override
    public void close() {
        try { if (consumer != null) consumer.close(); } catch (Exception ignored) {}
        try { if (session != null) session.close(); } catch (Exception ignored) {}
        try { if (connection != null) connection.close(); } catch (Exception ignored) {}
        ready = false;
    }
}