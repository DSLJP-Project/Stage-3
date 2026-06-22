package es.ulpgc.searchengine.crawler;

import com.google.gson.Gson;
import org.apache.activemq.ActiveMQConnectionFactory;

import javax.jms.Connection;
import javax.jms.DeliveryMode;
import javax.jms.MessageProducer;
import javax.jms.Session;
import javax.jms.TextMessage;
import java.util.LinkedHashMap;
import java.util.Map;

/** Persistent publisher with a reconnect attempt, suitable for Swarm startup races. */
public class BrokerPublisher implements AutoCloseable {

    private Connection connection;
    private Session session;
    private MessageProducer producer;
    private boolean ready;
    private final Gson gson = new Gson();

    public BrokerPublisher() {
        reconnect();
    }

    public synchronized boolean publish(Map<String, Object> document) {
        if (!ready) reconnect();
        if (!ready) return false;
        try {
            int bookId = ((Number) document.get("book_id")).intValue();
            Map<String, Object> event = new LinkedHashMap<>(document);
            event.put("eventType", "document.ingested");
            event.put("timestamp", System.currentTimeMillis());
            TextMessage message = session.createTextMessage(gson.toJson(event));
            message.setIntProperty("bookId", bookId);
            message.setStringProperty("eventType", "document.ingested");
            producer.send(message);
            System.out.println("[BrokerPublisher] Queued document.ingested for book " + bookId);
            return true;
        } catch (Exception e) {
            System.err.println("[BrokerPublisher] Send failed: " + e.getMessage());
            close();
            return false;
        }
    }

    public synchronized boolean isReady() {
        if (!ready) reconnect();
        return ready;
    }

    private synchronized void reconnect() {
        if (ready) return;
        close();
        try {
            String brokerUrl = System.getenv().getOrDefault("BROKER_URL", "tcp://activemq:61616");
            String queueName = System.getenv().getOrDefault("INGESTION_QUEUE", "document.ingested");
            ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(brokerUrl);
            connection = factory.createConnection();
            connection.start();
            session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            producer = session.createProducer(session.createQueue(queueName));
            producer.setDeliveryMode(DeliveryMode.PERSISTENT);
            ready = true;
            System.out.println("[BrokerPublisher] Connected to " + brokerUrl);
        } catch (Exception e) {
            System.err.println("[BrokerPublisher] Broker unavailable: " + e.getMessage());
            close();
        }
    }

    @Override
    public synchronized void close() {
        try { if (producer != null) producer.close(); } catch (Exception ignored) {}
        try { if (session != null) session.close(); } catch (Exception ignored) {}
        try { if (connection != null) connection.close(); } catch (Exception ignored) {}
        producer = null;
        session = null;
        connection = null;
        ready = false;
    }
}
