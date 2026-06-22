package es.ulpgc.searchengine.benchmarks;

import io.javalin.Javalin;
import es.ulpgc.searchengine.benchmarks.repository.DatamartMongo;
import es.ulpgc.searchengine.benchmarks.hazelcast.HazelcastIndex;
import es.ulpgc.searchengine.benchmarks.engine.BenchmarkEngine;

public class BenchmarkApp {
    public static void main(String[] args) {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "7004"));

        String mongoUri = System.getenv().getOrDefault("MONGO_URI", "mongodb://mongo:27017");
        String dbName = System.getenv().getOrDefault("MONGO_DB", "searchengine");
        String collection = System.getenv().getOrDefault("MONGO_COLLECTION", "books");

        // URLs de los servicios para el BenchmarkRunner (fases 1-4 de Stage 3)
        String crawlerUrls = System.getenv().getOrDefault("CRAWLER_URLS", "http://crawler-0:7007");
        String indexingUrl  = System.getenv().getOrDefault("INDEXING_URL", "http://indexer-0:7002");
        String searchUrl    = System.getenv().getOrDefault("SEARCH_URL", "http://nginx");

        DatamartMongo repo = new DatamartMongo(mongoUri, dbName, collection);
        HazelcastIndex hzIndex = new HazelcastIndex();
        BenchmarkEngine engine = new BenchmarkEngine(repo, hzIndex);
        BenchmarkRunner runner = new BenchmarkRunner(crawlerUrls, indexingUrl, searchUrl);

        BenchmarkController controller = new BenchmarkController(engine, runner);

        Javalin app = Javalin.create(cfg -> cfg.http.defaultContentType = "application/json").start(port);
        controller.register(app);

        app.get("/health", ctx -> ctx.status(200).result("OK"));
        app.get("/ready", ctx -> {
            boolean dbOk = repo.testConnection();
            boolean hzOk = hzIndex.isConnected();
            ctx.status(dbOk && hzOk ? 200 : 503)
                    .json(java.util.Map.of("db", dbOk, "hazelcast", hzOk));
        });

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { hzIndex.close(); } catch (Exception ignored) {}
            try { repo.close(); } catch (Exception ignored) {}
            try { app.stop(); } catch (Exception ignored) {}
        }));

        System.out.println("[BenchmarkApp] Benchmark Service running on port " + port +
                " using MongoDB " + mongoUri + "/" + dbName + "." + collection);
        System.out.println("[BenchmarkApp] BenchmarkRunner targets: crawlers=" + crawlerUrls +
                " indexing=" + indexingUrl + " search=" + searchUrl);
        System.out.println("[BenchmarkApp] Run phases via: GET /benchmark/run/{baseline|scaling|load|failure|all}");
    }
}
