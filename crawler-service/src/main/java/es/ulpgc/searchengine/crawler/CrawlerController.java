package es.ulpgc.searchengine.crawler;

import io.javalin.Javalin;
import io.javalin.http.Context;

import java.util.Map;

public class CrawlerController {

    private final CrawlerService crawler;
    private final BrokerPublisher publisher;

    public CrawlerController(CrawlerService crawler, BrokerPublisher publisher) {
        this.crawler = crawler;
        this.publisher = publisher;
    }

    public void register(Javalin app) {
        // Coincide con lo que queremos: /crawl/{bookId}
        app.post("/crawl/{bookId}", this::crawl);
    }

    private void crawl(Context ctx) {

        int bookId = Integer.parseInt(ctx.pathParam("bookId"));

        String path = crawler.crawl(bookId);

        if (path != null) {
            Map<String, Object> document = Map.of(
                    "book_id", bookId,
                    "path", path
            );

            publisher.publish(document);

            ctx.status(200).json(Map.of(
                    "status", "processed",
                    "partitioned", true,
                    "book_id", bookId,
                    "path", path
            ));
        } else {
            ctx.status(200).json(Map.of(
                    "status", "ignored",
                    "partitioned", true,
                    "book_id", bookId
            ));
        }
    }
}
