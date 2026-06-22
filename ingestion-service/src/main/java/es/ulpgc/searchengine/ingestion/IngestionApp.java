package es.ulpgc.searchengine.ingestion;

import com.google.gson.Gson;
import io.javalin.Javalin;

public class IngestionApp {

    public static void main(String[] args) {
        Gson gson = new Gson();

        IngestionEngine engine = new IngestionEngine();
        System.out.println("[Ingestion] datalakePath=" + engine.getDatalakePath());
        System.out.println("[Ingestion] peers=" + engine.getPeersRaw());
        System.out.println("[Ingestion] rf=" + engine.getReplicationFactor());

        IngestionController controller = new IngestionController(engine, gson);

        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "7001"));

        Javalin app = Javalin.create(cfg -> {
            cfg.http.defaultContentType = "application/json";
        });

        controller.register(app);

        app.start(port);
        System.out.println("[Ingestion] started on port " + port);
    }
}
