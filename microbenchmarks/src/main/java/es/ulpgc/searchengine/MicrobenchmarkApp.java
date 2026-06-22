package es.ulpgc.searchengine;

import es.ulpgc.searchengine.hazelcast.HazelcastIndex;
import es.ulpgc.searchengine.repository.DatamartMongo;
import io.javalin.Javalin;

public class MicrobenchmarkApp {
    public static void main(String[] args) {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "7005"));

        String mongoUri = System.getenv().getOrDefault("MONGO_URI", "mongodb://mongo:27017");
        String dbName = System.getenv().getOrDefault("MONGO_DB", "searchengine");
        String collection = System.getenv().getOrDefault("MONGO_COLLECTION", "books");

        DatamartMongo repo = new DatamartMongo(mongoUri, dbName, collection);
        HazelcastIndex hzIndex = new HazelcastIndex();
        MicrobenchmarkEngine engine = new MicrobenchmarkEngine(repo, hzIndex);

        MicrobenchmarkController controller = new MicrobenchmarkController(engine);

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

        System.out.println("[MicrobenchmarkApp] Microbenchmark Service running on port " + port +
                " using MongoDB " + mongoUri + "/" + dbName + "." + collection);
    }
}
