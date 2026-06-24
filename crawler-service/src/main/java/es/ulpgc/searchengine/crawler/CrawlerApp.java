package es.ulpgc.searchengine.crawler;

import io.javalin.Javalin;

public class CrawlerApp {

    public static void main(String[] args) {

        int port = Integer.parseInt(
                System.getenv().getOrDefault("PORT", "7007")
        );

        int crawlerId = Integer.parseInt(
                System.getenv().getOrDefault("CRAWLER_ID", "0")
        );

        int numCrawlers = Integer.parseInt(
                System.getenv().getOrDefault("NUM_CRAWLERS", "1")
        );

        String datalakePath = System.getenv().getOrDefault("DATALAKE_PATH", "/app/datalake");

        System.out.printf(
                "[CrawlerApp] Starting crawler %d of %d. DATALAKE_PATH=%s%n",
                crawlerId, numCrawlers, datalakePath
        );

        DatalakePartitioner partitioner =
                new DatalakePartitioner(crawlerId, numCrawlers);

        Javalin app = Javalin.create(cfg ->
                cfg.http.defaultContentType = "application/json"
        ).start(port);

        CrawlerService crawlerService =
                new CrawlerService(partitioner, datalakePath);

        BrokerPublisher publisher = new BrokerPublisher();

        CrawlerController controller =
                new CrawlerController(crawlerService, publisher);

        controller.register(app);

        app.get("/health", ctx -> ctx.result("OK"));
        app.get("/ready", ctx -> ctx.result("OK"));

        System.out.println("[CrawlerApp] Crawler Service running on port " + port);
    }
}
