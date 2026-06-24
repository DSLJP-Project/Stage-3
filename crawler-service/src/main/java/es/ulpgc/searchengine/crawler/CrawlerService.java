package es.ulpgc.searchengine.crawler;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Map;

public class CrawlerService {

    private static final String GUTENBERG_URL =
            "https://www.gutenberg.org/cache/epub/%d/pg%d.txt";
    private static final String START_MARKER =
            "*** START OF THE PROJECT GUTENBERG EBOOK";
    private static final String END_MARKER =
            "*** END OF THE PROJECT GUTENBERG EBOOK";

    private final DatalakePartitioner partitioner;
    private final String datalakeBasePath;
    private final HttpClient httpClient;

    public CrawlerService(DatalakePartitioner partitioner, String datalakeBasePath) {
        this.partitioner = partitioner;
        this.datalakeBasePath = datalakeBasePath;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public boolean process(Map<String, Object> document) {
        if (document == null) return false;
        Object idObj = document.get("book_id");
        if (!(idObj instanceof Number)) return false;
        int bookId = ((Number) idObj).intValue();
        return crawl(bookId) != null;
    }

    public String crawl(int bookId) {
        if (!partitioner.owns(bookId)) {
            System.out.printf("[CrawlerService] Crawler %d does NOT own book %d — ignored%n",
                    partitioner.getCrawlerId(), bookId);
            return null;
        }

        try {
            Path partitionPath = Paths.get(datalakeBasePath,
                    "partition-" + partitioner.getCrawlerId());
            Files.createDirectories(partitionPath);

            Path docDir = partitionPath.resolve("book-" + bookId);
            Files.createDirectories(docDir);

            Path contentFile = docDir.resolve("content.txt");
            Path headerFile  = docDir.resolve("header.txt");

            // Idempotencia: si ya existe con contenido, no volver a descargar
            if (Files.exists(contentFile) && Files.size(contentFile) > 0) {
                System.out.printf("[CrawlerService] Book %d already downloaded, skipping%n", bookId);
                return docDir.toString();
            }

            // ── Descarga real de Project Gutenberg ────────────────────────
            String url = String.format(GUTENBERG_URL, bookId, bookId);
            System.out.printf("[CrawlerService] Crawler %d downloading book %d from %s%n",
                    partitioner.getCrawlerId(), bookId, url);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();

            HttpResponse<String> resp = httpClient.send(req,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (resp.statusCode() != 200) {
                System.err.printf("[CrawlerService] HTTP %d for book %d — skipping%n",
                        resp.statusCode(), bookId);
                return null;
            }

            String raw = resp.body();

            // ── Verificar marcadores Gutenberg ────────────────────────────
            if (!raw.contains(START_MARKER) || !raw.contains(END_MARKER)) {
                System.err.printf("[CrawlerService] Book %d missing Gutenberg markers — skipping%n",
                        bookId);
                return null;
            }

            // ── Separar header, body y footer ─────────────────────────────
            String[] splitStart = raw.split(java.util.regex.Pattern.quote(START_MARKER), 2);
            String header       = splitStart[0].strip();
            String bodyAndFooter = splitStart[1];

            String[] splitEnd = bodyAndFooter.split(
                    java.util.regex.Pattern.quote(END_MARKER), 2);
            String body = splitEnd[0].strip();

            // ── Persistir en datalake ─────────────────────────────────────
            Files.writeString(headerFile,  header, StandardCharsets.UTF_8);
            Files.writeString(contentFile, body,   StandardCharsets.UTF_8);

            System.out.printf("[CrawlerService] Crawler %d downloaded book %d (%,d chars)%n",
                    partitioner.getCrawlerId(), bookId, body.length());

            return docDir.toString();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.printf("[CrawlerService] Interrupted while downloading book %d%n", bookId);
            return null;
        } catch (Exception e) {
            System.err.printf("[CrawlerService] ERROR crawling book %d: %s%n",
                    bookId, e.getMessage());
            return null;
        }
    }
}