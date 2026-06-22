package es.ulpgc.searchengine.ingestion.messaging;

import org.apache.activemq.ActiveMQConnectionFactory;

import javax.jms.*;

public class IngestionPublisher {

    private final String brokerUrl;
    private final String queueName;

    public IngestionPublisher(String brokerUrl, String queueName) {
        this.brokerUrl = brokerUrl;
        this.queueName = queueName;
    }

    public void publish(String bookId) {
        Connection connection = null;
        Session session = null;

        try {
            ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(brokerUrl);
            connection = factory.createConnection();
            connection.start();

            session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);

            Destination destination = session.createQueue(queueName);
            MessageProducer producer = session.createProducer(destination);
            producer.setDeliveryMode(DeliveryMode.PERSISTENT);

            TextMessage msg = session.createTextMessage(bookId);
            producer.send(msg);

        } catch (Exception e) {
            System.err.println("[IngestionPublisher] publish failed: " + e.getMessage());
        } finally {
            try { if (session != null) session.close(); } catch (Exception ignored) {}
            try { if (connection != null) connection.close(); } catch (Exception ignored) {}
        }
    }
}
