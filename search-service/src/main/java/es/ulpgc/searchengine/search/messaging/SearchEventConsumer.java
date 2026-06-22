package es.ulpgc.searchengine.search.messaging;

import javax.jms.*;
import org.apache.activemq.ActiveMQConnectionFactory;
import es.ulpgc.searchengine.search.repository.DatamartMongo;
import es.ulpgc.searchengine.search.hazelcast.HazelcastIndex;

public class SearchEventConsumer implements AutoCloseable {

    private Connection connection;
    private Session session;
    private MessageConsumer consumer;
    private boolean ready = false;

    public SearchEventConsumer(DatamartMongo repo, HazelcastIndex hz) {
        try {
            String brokerUrl = System.getenv().getOrDefault("BROKER_URL", "tcp://activemq:61616");
            String queueName = "book-indexed";

            ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(brokerUrl);

            connection = factory.createConnection();
            connection.start();

            session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            Destination queue = session.createQueue(queueName);

            consumer = session.createConsumer(queue);

            consumer.setMessageListener(msg -> {
                try {
                    if (msg instanceof TextMessage tm) {
                        String body = tm.getText();
                        System.out.println("[SearchConsumer] Received event: " + body);

                        String eventType = msg.getStringProperty("eventType");
                        if (eventType != null && !"document.indexed".equals(eventType)) {
                            System.out.println("[SearchConsumer] Ignoring event type: " + eventType);
                            return;
                        }

                        // 🔥 YA NO REINDEXAMOS DESDE EL SEARCH-SERVICE
                        // El indexer ya actualizó Hazelcast.
                        // Aquí solo podrías invalidar caches si quisieras.

                        System.out.println("[SearchConsumer] Event processed (no reindex needed)");
                    }
                } catch (Exception e) {
                    System.err.println("[SearchConsumer] Error processing message: " + e.getMessage());
                }
            });

            ready = true;
            System.out.println("[SearchConsumer] Listening on queue '" + queueName + "' at " + brokerUrl);

        } catch (Exception e) {
            System.err.println("[SearchConsumer] Failed to initialize: " + e.getMessage());
            close();
        }
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
