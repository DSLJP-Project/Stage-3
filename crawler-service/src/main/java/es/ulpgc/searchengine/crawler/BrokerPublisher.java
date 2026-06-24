package es.ulpgc.searchengine.crawler;

import com.google.gson.Gson;
import javax.jms.Connection;
import javax.jms.DeliveryMode;
import javax.jms.Destination;
import javax.jms.MessageProducer;
import javax.jms.Session;
import javax.jms.TextMessage;
import org.apache.activemq.ActiveMQConnectionFactory;

import java.util.HashMap;
import java.util.Map;

public class BrokerPublisher implements AutoCloseable {

    private Connection connection;
    private Session session;
    private MessageProducer producer;
    private boolean ready = false;

    private final Gson gson = new Gson();

    public BrokerPublisher() {
        try {
            String brokerUrl = System.getenv().getOrDefault("BROKER_URL", "tcp://activemq:61616");
            String queueName = System.getenv().getOrDefault("INGESTION_QUEUE", "document.ingested");

            System.out.println("[BrokerPublisher] Connecting to " + brokerUrl + " queue=" + queueName);

            ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(brokerUrl);

            connection = factory.createConnection();
            connection.start();

            session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            Destination queue = session.createQueue(queueName);

            producer = session.createProducer(queue);
            producer.setDeliveryMode(DeliveryMode.PERSISTENT);

            ready = true;

            System.out.println("[BrokerPublisher] ActiveMQ connected OK");

        } catch (Exception e) {
            System.err.println("[BrokerPublisher] ERROR initializing JMS: " + e.getMessage());
            close();
        }
    }

    public void publish(Map<String, Object> document) {
        if (!ready) {
            System.err.println("[BrokerPublisher] Not ready, cannot publish");
            return;
        }

        try {
            int bookId = ((Number) document.get("book_id")).intValue();
            String path = (String) document.get("path");

            Map<String, Object> evt = new HashMap<>();
            evt.put("eventType", "document.ingested");
            evt.put("book_id", bookId);
            evt.put("path", path);
            evt.put("timestamp", System.currentTimeMillis());

            String json = gson.toJson(evt);

            TextMessage message = session.createTextMessage(json);
            message.setIntProperty("bookId", bookId);
            message.setStringProperty("eventType", "document.ingested");

            producer.send(message);

            System.out.println("[BrokerPublisher] Sent event: document.ingested for book " + bookId);

        } catch (Exception e) {
            System.err.println("[BrokerPublisher] ERROR sending message: " + e.getMessage());
        }
    }

    @Override
    public void close() {
        try { if (producer != null) producer.close(); } catch (Exception ignored) {}
        try { if (session != null) session.close(); } catch (Exception ignored) {}
        try { if (connection != null) connection.close(); } catch (Exception ignored) {}
        ready = false;
    }
}
