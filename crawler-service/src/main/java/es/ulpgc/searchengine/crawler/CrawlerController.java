package es.ulpgc.searchengine.crawler;

import com.google.gson.Gson;
import io.javalin.Javalin;
import io.javalin.http.Context;

import java.util.Map;

public class CrawlerController {

    private final CrawlerService crawler;
    private final BrokerPublisher publisher;
    private final Gson gson = new Gson();

    public CrawlerController(CrawlerService crawler, BrokerPublisher publisher) {
        this.crawler = crawler;
        this.publisher = publisher;
    }

    public void register(Javalin app) {
        app.post("/crawl/{bookId}", this::crawl);
        app.post("/replicas/{bookId}", this::storeReplica);
        app.get("/documents/{bookId}", this::getDocument);
    }

    private void crawl(Context ctx) {
        int bookId = Integer.parseInt(ctx.pathParam("bookId"));
        CrawlerService.CrawlResult result = crawler.crawl(bookId);

        if (result == null) {
            ctx.status(409).json(Map.of("status", "not_owner_or_download_failed", "book_id", bookId));
            return;
        }
        if (!result.replicationSatisfied()) {
            ctx.status(503).json(Map.of(
                    "status", "replication_incomplete",
                    "book_id", bookId,
                    "replicas_confirmed", result.locations().size(),
                    "replication_factor", Integer.parseInt(System.getenv().getOrDefault("REPLICATION_FACTOR", "1"))
            ));
            return;
        }

        boolean queued = publisher.publish(Map.of(
                "book_id", bookId,
                "source_urls", result.locations(),
                "content_hash", result.contentHash()
        ));
        if (!queued) {
            ctx.status(503).json(Map.of("status", "broker_unavailable", "book_id", bookId));
            return;
        }
        ctx.status(202).json(Map.of(
                "status", "replicated_and_queued",
                "book_id", bookId,
                "replicas_confirmed", result.locations().size(),
                "source_urls", result.locations()
        ));
    }

    private void storeReplica(Context ctx) {
        int bookId = Integer.parseInt(ctx.pathParam("bookId"));
        ReplicaPayload payload = gson.fromJson(ctx.body(), ReplicaPayload.class);
        if (payload == null || payload.content == null) {
            ctx.status(400).json(Map.of("error", "content is required"));
            return;
        }
        boolean stored = crawler.storeReplica(bookId, payload.header == null ? "" : payload.header, payload.content);
        ctx.status(stored ? 201 : 500).json(Map.of("book_id", bookId, "stored", stored));
    }

    private void getDocument(Context ctx) {
        int bookId = Integer.parseInt(ctx.pathParam("bookId"));
        CrawlerService.StoredDocument document = crawler.getDocument(bookId);
        if (document == null) {
            ctx.status(404).json(Map.of("book_id", bookId, "error", "not_found"));
            return;
        }
        ctx.json(Map.of("book_id", bookId, "header", document.header(), "content", document.content()));
    }

    private static class ReplicaPayload {
        String header;
        String content;
    }
}
