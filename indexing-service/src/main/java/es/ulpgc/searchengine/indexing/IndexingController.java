package es.ulpgc.searchengine.indexing;

import io.javalin.Javalin;
import io.javalin.http.Context;
import com.google.gson.Gson;
import es.ulpgc.searchengine.indexing.repository.DatamartMongo;
import es.ulpgc.searchengine.indexing.hazelcast.HazelcastIndex;

import java.util.Map;

public class IndexingController {
    private static final Gson gson = new Gson();
    private final DatamartMongo repo;
    private final HazelcastIndex hz;

    public IndexingController(DatamartMongo repo, HazelcastIndex hz) {
        this.repo = repo;
        this.hz = hz;
    }

    public void register(Javalin app) {
        app.get("/index", this::indexBook);
        app.post("/index/update/{bookId}", this::updateIndex);
        app.get("/index/status/{bookId}", this::indexStatus);

        app.get("/status", ctx -> ctx.result(
                gson.toJson(Map.of("service", "indexing", "status", "running"))
        ));

        app.get("/health", ctx -> ctx.result(
                gson.toJson(Map.of("status", "ok"))
        ));

        app.get("/ready", ctx -> {
            boolean mongoOk = repo != null && repo.testConnection();
            boolean hzOk = hz != null && hz.isConnected();
            if (mongoOk && hzOk) {
                ctx.status(200).result(
                        gson.toJson(Map.of("hazelcast", true, "db", true))
                );
            } else {
                ctx.status(503).result(
                        gson.toJson(Map.of("hazelcast", hzOk, "db", mongoOk))
                );
            }
        });
    }

    // NUEVO MÉTODO Stage 3
    private void indexBook(Context ctx) {
        String id = ctx.queryParam("bookId");
        if (id == null) {
            ctx.status(400).result(gson.toJson(Map.of("error", "missing_bookId")));
            return;
        }

        int bookId = Integer.parseInt(id);
        var book = repo.getBook(bookId);

        if (book == null) {
            ctx.status(404).result(gson.toJson(Map.of("error", "book_not_found")));
            return;
        }

        hz.indexBook(book);

        ctx.status(200).result(gson.toJson(Map.of(
                "book_id", bookId,
                "status", "indexed",
                "content_hash", book.get("content_hash")
        )));
    }

    private void updateIndex(Context ctx) {
        try {
            int bookId = Integer.parseInt(ctx.pathParam("bookId"));
            var book = repo.getBook(bookId);
            if (book == null) {
                ctx.status(404).result(gson.toJson(Map.of("error", "book_not_found")));
                return;
            }

            hz.indexBook(book);
            ctx.status(200).result(gson.toJson(Map.of(
                    "book_id", bookId,
                    "status", "indexed",
                    "content_hash", book.get("content_hash")
            )));
        } catch (Exception e) {
            ctx.status(500).result(gson.toJson(Map.of("error", "indexing_failed")));
        }
    }

    private void indexStatus(Context ctx) {
        int bookId = Integer.parseInt(ctx.pathParam("bookId"));
        var book = repo.getBook(bookId);
        if (book == null) {
            ctx.status(404).json(Map.of("book_id", bookId, "indexed", false));
            return;
        }
        ctx.json(Map.of("book_id", bookId, "indexed", true,
                "content_hash", book.getOrDefault("content_hash", "")));
    }
}
