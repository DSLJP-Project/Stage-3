package es.ulpgc.searchengine.ingestion;

import com.google.gson.Gson;
import io.javalin.Javalin;
import io.javalin.http.Context;

import java.util.Map;

public class IngestionController {

    private final IngestionEngine engine;
    private final Gson gson;

    public IngestionController(IngestionEngine engine, Gson gson) {
        this.engine = engine;
        this.gson = gson;
    }

    public void register(Javalin app) {
        app.get("/ingest", this::ingestFromUrl);
        app.post("/replica/ingest", this::replicaIngest);
        app.post("/ingest/{bookId}", this::ingest);
        app.get("/ingest/status/{bookId}", this::status);
        app.get("/status", ctx -> ctx.result(gson.toJson(
                Map.of("service", "ingestion", "status", "running"))));
        app.get("/health", ctx -> ctx.status(200).result("OK"));  // <-- AÑADIDO
    }


    private void ingest(Context ctx) {
        String bookId = ctx.pathParam("bookId");
        String body = ctx.body();

        Map<String, Object> result = engine.ingest(bookId, body);

        ctx.status(200).result(gson.toJson(result));
    }

    private void replicaIngest(Context ctx) {
        ReplicaPayload payload = gson.fromJson(ctx.body(), ReplicaPayload.class);
        if (payload == null || payload.bookId == null) {
            ctx.status(400).result(gson.toJson(Map.of("ok", false, "error", "invalid payload")));
            return;
        }

        boolean ok = engine.storeReplica(payload.bookId, payload.content == null ? "" : payload.content);

        if (ok) {
            ctx.status(200).result(gson.toJson(Map.of("ok", true)));
        } else {
            ctx.status(500).result(gson.toJson(Map.of("ok", false)));
        }
    }

    private void status(Context ctx) {
        String bookId = ctx.pathParam("bookId");
        Map<String, Object> result = engine.status(bookId);
        ctx.status(200).result(gson.toJson(result));
    }

    private static class ReplicaPayload {
        String bookId;
        String content;
    }

    private void ingestFromUrl(Context ctx) {
        String url = ctx.queryParam("url");
        if (url == null || url.isBlank()) {
            ctx.status(400).result(gson.toJson(Map.of("ok", false, "error", "missing url")));
            return;
        }

        try {
            // Descargar contenido
            String content = new String(new java.net.URL(url).openStream().readAllBytes());

            // Generar bookId único
            String bookId = java.util.UUID.randomUUID().toString();

            // Ingestar usando el motor
            Map<String, Object> result = engine.ingest(bookId, content);

            ctx.status(200).result(gson.toJson(result));

        } catch (Exception e) {
            ctx.status(500).result(gson.toJson(Map.of("ok", false, "error", e.getMessage())));
        }
    }

}