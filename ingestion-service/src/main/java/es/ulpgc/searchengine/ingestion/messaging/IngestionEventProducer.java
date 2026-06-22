package es.ulpgc.searchengine.ingestion.messaging;

import javax.jms.*;
import org.apache.activemq.ActiveMQConnectionFactory;

public class IngestionEventProducer {

    private Connection connection;
    private Session session;
    private MessageProducer producer;
    private boolean ready = false;

    private final String brokerUrl = System.getenv().getOrDefault("BROKER_URL", "tcp://activemq:61616");
    private final String queueName = System.getenv().getOrDefault("INGESTION_QUEUE", "document.ingested");

    public IngestionEventProducer() {
        try {
            ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(brokerUrl);
            connection = factory.createConnection();
            connection.start();
            session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            Destination queue = session.createQueue(queueName);
            producer = session.createProducer(queue);
            ready = true;
            System.out.println("[IngestionProducer] Connected to ActiveMQ at " + brokerUrl);
        } catch (Exception e) {
            System.err.println("[IngestionProducer] Failed to initialize: " + e.getMessage());
            close();
        }
    }

    public boolean isReady() {
        return ready;
    }

    public void publishBookIngested(int bookId, String path) {
        if (!ready) return;
        try {
            String json = String.format(
                    "{\"book_id\": %d, \"path\": \"%s\"}",
                    bookId, path
            );
            TextMessage msg = session.createTextMessage(json);
            producer.send(msg);
            System.out.println("[IngestionProducer] Published event: " + json);
        } catch (Exception e) {
            System.err.println("[IngestionProducer] Error publishing message: " + e.getMessage());
        }
    }


    public void close() {
        try { if (producer != null) producer.close(); } catch (Exception ignored) {}
        try { if (session != null) session.close(); } catch (Exception ignored) {}
        try { if (connection != null) connection.close(); } catch (Exception ignored) {}
        ready = false;
    }
}
