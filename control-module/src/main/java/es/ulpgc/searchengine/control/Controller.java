package es.ulpgc.searchengine.control;

import io.javalin.Javalin;
import io.javalin.http.Context;
import com.google.gson.Gson;

import javax.jms.*;
import org.apache.activemq.ActiveMQConnectionFactory;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class Controller {

    private static final Gson gson = new Gson();

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final String indexingUrl =
            System.getenv().getOrDefault("INDEXING_URL",       "http://indexing-service:7002");
    private final String searchUrl =
            System.getenv().getOrDefault("SEARCH_URL",         "http://nginx");
    private final String benchmarksUrl =
            System.getenv().getOrDefault("BENCHMARKS_URL",     "http://benchmarks:7004");
    private final List<String> crawlerUrls = crawlerUrls();
    private final String brokerUrl =
            System.getenv().getOrDefault("BROKER_URL",         "tcp://activemq:61616");
    private final String reindexTopic =
            System.getenv().getOrDefault("REINDEX_TOPIC",      "reindex.request");

    public void register(Javalin app) {
        app.get("/control/status",          this::status);
        app.get("/control/ready",           this::ready);
        app.get("/control/benchmark",       this::benchmark);
        app.post("/control/crawl/{bookId}", this::crawl);
        app.post("/control/reindex",        this::reindex);
        app.get("/health", ctx -> ctx.status(200).result("OK"));
    }

    // ─── Estado de todos los servicios ────────────────────────────────────────
    private void status(Context ctx) {
        Map<String, Object> statuses = new HashMap<>();
        statuses.put("indexing",        ping(indexingUrl        + "/health"));
        statuses.put("search",          ping(searchUrl          + "/health"));
        statuses.put("benchmarks",      ping(benchmarksUrl      + "/health"));
        for (int i = 0; i < crawlerUrls.size(); i++)
            statuses.put("datalake_" + i, ping(crawlerUrls.get(i) + "/health"));
        ctx.json(statuses);
    }

    // ─── Readiness de todos los servicios ─────────────────────────────────────
    private void ready(Context ctx) {
        Map<String, Object> readiness = new HashMap<>();
        readiness.put("indexing",        ping(indexingUrl        + "/ready"));
        readiness.put("search",          ping(searchUrl          + "/ready"));
        readiness.put("benchmarks",      ping(benchmarksUrl      + "/ready"));
        for (int i = 0; i < crawlerUrls.size(); i++)
            readiness.put("datalake_" + i, ping(crawlerUrls.get(i) + "/ready"));
        ctx.json(readiness);
    }

    // ─── Benchmark coordinado ─────────────────────────────────────────────────
    private void benchmark(Context ctx) {
        Map<String, Object> results = new HashMap<>();
        results.put("basic",   fetch(searchUrl + "/search?q=adventure"));
        results.put("phrase",  fetch(searchUrl + "/search/phrase?phrase=the+end"));
        results.put("boolean", fetch(searchUrl + "/search/advanced?q=love+AND+war"));
        results.put("range",   fetch(searchUrl + "/search/range?q=sea&start_year=1800&end_year=1900"));
        ctx.json(results);
    }

    // ─── Dispara el crawler para un libro ─────────────────────────────────────
    private void crawl(Context ctx) {
        int bookId;
        try {
            bookId = Integer.parseInt(ctx.pathParam("bookId"));
        } catch (NumberFormatException e) {
            ctx.status(400).json(Map.of("error", "invalid bookId"));
            return;
        }

        try {
            String crawlerUrl = crawlerUrls.get(Math.floorMod(bookId, crawlerUrls.size()));
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(crawlerUrl + "/crawl/" + bookId))
                    .timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();

            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            ctx.status(resp.statusCode()).result(resp.body());

        } catch (Exception e) {
            ctx.status(500).json(Map.of(
                    "error",   "Crawler unreachable",
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * Publica un evento reindex.request en ActiveMQ.
     *
     * NOTA IMPORTANTE: en este proyecto, javax.jms.Connection / Session
     * (provenientes de jakarta.jms-api + geronimo-jms_1.1_spec transitivo de
     * activemq-client) NO implementan java.lang.AutoCloseable. Por eso NO se
     * puede usar try-with-resources (try (Connection c = ...) {...}) — daría
     * "Incompatible types. Found: javax.jms.Connection, required:
     * java.lang.AutoCloseable". En su lugar se usa try/finally con close()
     * manual, que es válido para JMS 1.1/2.x con o sin AutoCloseable.
     */
    private void reindex(Context ctx) {
        Connection connection = null;
        Session session = null;
        MessageProducer producer = null;

        try {
            ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(brokerUrl);
            connection = factory.createConnection();
            connection.start();

            session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);

            Destination topic = session.createTopic(reindexTopic);
            producer = session.createProducer(topic);
            producer.setDeliveryMode(DeliveryMode.PERSISTENT);

            TextMessage msg = session.createTextMessage(gson.toJson(Map.of(
                    "eventType", "reindex.request",
                    "timestamp", System.currentTimeMillis()
            )));
            producer.send(msg);

            System.out.println("[Controller] reindex.request sent to topic: " + reindexTopic);

            ctx.status(200).json(Map.of(
                    "status", "reindex.request sent",
                    "topic",  reindexTopic
            ));

        } catch (Exception e) {
            System.err.println("[Controller] Error sending reindex: " + e.getMessage());
            ctx.status(500).json(Map.of("error", e.getMessage()));

        } finally {
            if (producer != null) {
                try { producer.close(); } catch (Exception ignored) {}
            }
            if (session != null) {
                try { session.close(); } catch (Exception ignored) {}
            }
            if (connection != null) {
                try { connection.close(); } catch (Exception ignored) {}
            }
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private String ping(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(3))
                    .GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200 ? "ok" : "error:" + resp.statusCode();
        } catch (Exception e) {
            return "unreachable: " + e.getMessage();
        }
    }

    private Object fetch(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            return gson.fromJson(resp.body(), Object.class);
        } catch (Exception e) {
            return Map.of("error", e.getMessage());
        }
    }

    private static List<String> crawlerUrls() {
        String configured = System.getenv().getOrDefault("CRAWLER_URLS",
                System.getenv().getOrDefault("CRAWLER_URL", "http://crawler-0:7007"));
        List<String> urls = new ArrayList<>();
        for (String value : configured.split(",")) {
            if (!value.isBlank()) urls.add(value.trim().replaceAll("/$", ""));
        }
        if (urls.isEmpty()) throw new java.lang.IllegalStateException("CRAWLER_URLS must contain at least one URL");
        return List.copyOf(urls);
    }
}
