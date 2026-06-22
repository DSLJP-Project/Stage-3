package es.ulpgc.searchengine.ingestion.messaging;

import javax.jms.*;
import org.apache.activemq.ActiveMQConnectionFactory;
import com.google.gson.Gson;
import java.util.Map;

public class EventPublisher {

    private final String brokerUrl;
    private final String brokerUser;
    private final String brokerPassword;
    private final String queueName;

    private Connection connection;
    private Session session;
    private MessageProducer producer;

    public EventPublisher(String brokerUrl, String brokerUser, String brokerPassword, String queueName) {
        this.brokerUrl = brokerUrl;
        this.brokerUser = brokerUser;
        this.brokerPassword = brokerPassword;
        this.queueName = queueName;
        init();
    }

    /** Inicializa conexión JMS con reintentos y credenciales */
    private void init() {
        int retries = 0;
        while (retries < 20) {
            try {
                System.out.println("[EventPublisher] Conectando a " + brokerUrl);
                ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(brokerUrl);
                if (brokerUser != null && brokerPassword != null) {
                    factory.setUserName(brokerUser);
                    factory.setPassword(brokerPassword);
                }

                this.connection = factory.createConnection();
                this.connection.start();

                this.session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
                Destination queue = session.createQueue(queueName);

                this.producer = session.createProducer(queue);
                // Asegurar persistencia de mensajes
                this.producer.setDeliveryMode(DeliveryMode.PERSISTENT);

                System.out.println("[EventPublisher] JMS inicializado correctamente.");
                return;

            } catch (Exception e) {
                retries++;
                System.err.println("[EventPublisher] ERROR al iniciar JMS. Reintentando... (" + retries + "/20) - " + e.getMessage());
                try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
            }
        }
        System.err.println("[EventPublisher] NO SE PUDO CONECTAR AL BROKER.");
    }

    /** Publica un evento enriquecido document.ingested con metadatos */
    public void publishBookIngested(int bookId, String contentHash, String path, int replicas) {
        try {
            if (producer == null) {
                System.err.println("[EventPublisher] JMS NO inicializado. Mensaje ignorado: " + bookId);
                return;
            }
            String payload = new Gson().toJson(Map.of(
                    "eventType", "document.ingested",
                    "bookId", bookId,
                    "contentHash", contentHash,
                    "path", path,
                    "replicas", replicas,
                    "timestamp", System.currentTimeMillis()
            ));

            TextMessage msg = session.createTextMessage(payload);
            // Propiedades útiles para consumidores idempotentes
            msg.setStringProperty("dedupKey", contentHash);
            msg.setIntProperty("bookId", bookId);

            producer.send(msg, DeliveryMode.PERSISTENT, Message.DEFAULT_PRIORITY, 0);
            System.out.println("[EventPublisher] Evento enviado: bookId=" + bookId + " replicas=" + replicas);

        } catch (Exception e) {
            System.err.println("[EventPublisher] Error enviando mensaje: " + e.getMessage());
        }
    }

    public boolean isConnected() {
        return connection != null && producer != null;
    }

    public void close() {
        try { if (producer != null) producer.close(); } catch (Exception ignored) {}
        try { if (session != null) session.close(); } catch (Exception ignored) {}
        try { if (connection != null) connection.close(); } catch (Exception ignored) {}
    }
}
