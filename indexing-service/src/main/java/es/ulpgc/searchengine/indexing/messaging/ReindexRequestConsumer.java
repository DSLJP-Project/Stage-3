package es.ulpgc.searchengine.indexing.messaging;

import es.ulpgc.searchengine.indexing.hazelcast.HazelcastIndex;
import es.ulpgc.searchengine.indexing.repository.DatamartMongo;
import org.apache.activemq.ActiveMQConnectionFactory;

import javax.jms.*;
import java.util.List;
import java.util.Map;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class ReindexRequestConsumer implements AutoCloseable {

    private Connection connection;
    private Session session;
    private MessageConsumer consumer;
    private boolean ready = false;

    private final DatamartMongo repo;
    private final HazelcastIndex hz;

    private final int indexerId;
    private final int indexerCount;

    public ReindexRequestConsumer(DatamartMongo repo, HazelcastIndex hz) {
        this.repo = repo;
        this.hz = hz;

        this.indexerId = Integer.parseInt(System.getenv().getOrDefault("INDEXER_ID", "0"));
        this.indexerCount = Integer.parseInt(System.getenv().getOrDefault("INDEXER_COUNT", "1"));

        try {
            String brokerUrl = System.getenv().getOrDefault("BROKER_URL", "tcp://activemq:61616");
            String topicName = System.getenv().getOrDefault("REINDEX_TOPIC", "reindex.request");

            ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(brokerUrl);
            connection = factory.createConnection();
            connection.start();

            session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);

            // Topic para broadcast: TODOS los indexers reciben el reindex
            Destination topic = session.createTopic(topicName);
            consumer = session.createConsumer(topic);

            consumer.setMessageListener(msg -> {
                try {
                    if (msg instanceof TextMessage tm) {
                        System.out.println("[ReindexConsumer] Reindex requested: " + tm.getText());
                        long generation = generationOf(tm.getText());
                        runDistributedReindex(generation);
                    }
                } catch (Exception e) {
                    System.err.println("[ReindexConsumer] Error: " + e.getMessage());
                }
            });

            ready = true;
            System.out.println("[ReindexConsumer] Listening to topic " + topicName +
                    " as indexerId=" + indexerId + " indexerCount=" + indexerCount);

        } catch (Exception e) {
            System.err.println("[ReindexConsumer] Failed to init: " + e.getMessage());
            close();
        }
    }

    private long generationOf(String text) {
        try {
            JsonObject event = JsonParser.parseString(text).getAsJsonObject();
            return event.has("timestamp") ? event.get("timestamp").getAsLong() : System.currentTimeMillis();
        } catch (Exception ignored) {
            return System.currentTimeMillis();
        }
    }

    private void runDistributedReindex(long generation) {
        // Solo el indexer 0 limpia el índice. Los demás esperan la barrera
        // distribuida para que ninguno inserte antes del clear().
        if (indexerId == 0) {
            hz.clearAll();
            hz.markReindexReady(generation);
        } else if (!hz.awaitReindexReady(generation, 10_000)) {
            System.err.println("[ReindexConsumer] Timed out waiting for indexer 0");
            return;
        }

        List<Map<String, Object>> books = repo.getAllBooks();
        int total = 0;

        for (Map<String, Object> book : books) {
            int bookId = (Integer) book.get("book_id");

            // Sharding simple: cada indexer indexa solo su parte
            if ((bookId % indexerCount) != indexerId) continue;

            hz.indexBook(book);
            total++;
        }

        System.out.println("[ReindexConsumer] Done. Indexed " + total +
                " books on this node (" + indexerId + "/" + indexerCount + ")");
    }

    public boolean isReady() {
        return ready;
    }

    @Override
    public void close() {
        try { if (consumer != null) consumer.close(); } catch (Exception ignored) {}
        try { if (session != null) session.close(); } catch (Exception ignored) {}
        try { if (connection != null) connection.close(); } catch (Exception ignored) {}
        ready = false;
    }
}
