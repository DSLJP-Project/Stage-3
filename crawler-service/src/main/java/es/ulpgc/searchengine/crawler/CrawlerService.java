package es.ulpgc.searchengine.crawler;

import com.google.gson.Gson;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** A crawler is also one physical datalake node. */
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
    private final String nodeUrl;
    private final List<String> peers;
    private final int replicationFactor;
    private final Gson gson = new Gson();

    public CrawlerService(DatalakePartitioner partitioner, String datalakeBasePath) {
        this.partitioner = partitioner;
        this.datalakeBasePath = datalakeBasePath;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.nodeUrl = withoutTrailingSlash(System.getenv().getOrDefault(
                "NODE_URL", "http://crawler-" + partitioner.getCrawlerId() + ":7007"));
        this.peers = parseUrls(System.getenv().getOrDefault("CRAWLER_PEERS", ""));
        this.replicationFactor = Math.max(1, parseInt("REPLICATION_FACTOR", 1));
    }

    public boolean process(Map<String, Object> document) {
        if (document == null || !(document.get("book_id") instanceof Number id)) return false;
        return crawl(id.intValue()) != null;
    }

    /**
     * Stores a primary document locally and sends it to enough peer datalake
     * nodes to satisfy R before the caller publishes an indexing event.
     */
    public CrawlResult crawl(int bookId) {
        if (!partitioner.owns(bookId)) {
            System.out.printf("[CrawlerService] Crawler %d does NOT own book %d - ignored%n",
                    partitioner.getCrawlerId(), bookId);
            return null;
        }

        try {
            StoredDocument existing = readFrom(primaryDirectory(bookId));
            String header;
            String content;

            if (existing != null) {
                System.out.printf("[CrawlerService] Book %d already stored; checking replicas%n", bookId);
                header = existing.header();
                content = existing.content();
            } else {
                String url = String.format(GUTENBERG_URL, bookId, bookId);
                System.out.printf("[CrawlerService] Crawler %d downloading book %d from %s%n",
                        partitioner.getCrawlerId(), bookId, url);
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(30))
                        .GET().build();
                HttpResponse<String> response = httpClient.send(request,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() != 200) {
                    System.err.printf("[CrawlerService] HTTP %d for book %d%n", response.statusCode(), bookId);
                    return null;
                }

                String raw = response.body();
                if (!raw.contains(START_MARKER) || !raw.contains(END_MARKER)) {
                    System.err.printf("[CrawlerService] Book %d has no Gutenberg markers%n", bookId);
                    return null;
                }

                String[] start = raw.split(java.util.regex.Pattern.quote(START_MARKER), 2);
                header = start[0].strip();
                String[] end = start[1].split(java.util.regex.Pattern.quote(END_MARKER), 2);
                content = end[0].strip();
                writeTo(primaryDirectory(bookId), header, content);
                System.out.printf("[CrawlerService] Stored primary book %d (%d chars)%n", bookId, content.length());
            }

            List<String> locations = new ArrayList<>();
            locations.add(nodeUrl);
            for (String peer : peers) {
                if (locations.size() >= replicationFactor) break;
                if (withoutTrailingSlash(peer).equals(nodeUrl)) continue;
                if (replicate(peer, bookId, header, content)) locations.add(withoutTrailingSlash(peer));
            }
            return new CrawlResult(bookId, header, content, List.copyOf(locations),
                    locations.size() >= replicationFactor);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            System.err.printf("[CrawlerService] ERROR crawling book %d: %s%n", bookId, e.getMessage());
            return null;
        }
    }

    /** Stores a replica received over the overlay network, not in a shared volume. */
    public boolean storeReplica(int bookId, String header, String content) {
        try {
            writeTo(replicaDirectory(bookId), header, content);
            System.out.printf("[CrawlerService] Stored replica of book %d on crawler %d%n",
                    bookId, partitioner.getCrawlerId());
            return true;
        } catch (Exception e) {
            System.err.printf("[CrawlerService] Cannot store replica %d: %s%n", bookId, e.getMessage());
            return false;
        }
    }

    /** Returns a primary or replica document so any indexer can retrieve it. */
    public StoredDocument getDocument(int bookId) {
        try {
            StoredDocument primary = readFrom(primaryDirectory(bookId));
            return primary != null ? primary : readFrom(replicaDirectory(bookId));
        } catch (Exception e) {
            return null;
        }
    }

    private boolean replicate(String peer, int bookId, String header, String content) {
        try {
            String json = gson.toJson(Map.of("header", header, "content", content));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(withoutTrailingSlash(peer) + "/replicas/" + bookId))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) return true;
            System.err.printf("[CrawlerService] Replica %s rejected book %d: HTTP %d%n",
                    peer, bookId, response.statusCode());
        } catch (Exception e) {
            System.err.printf("[CrawlerService] Cannot replicate book %d to %s: %s%n",
                    bookId, peer, e.getMessage());
        }
        return false;
    }

    private Path primaryDirectory(int bookId) {
        return Paths.get(datalakeBasePath, "partition-" + partitioner.getCrawlerId(), "book-" + bookId);
    }

    private Path replicaDirectory(int bookId) {
        return Paths.get(datalakeBasePath, "replicas", "book-" + bookId);
    }

    private static void writeTo(Path directory, String header, String content) throws Exception {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("header.txt"), header, StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("content.txt"), content, StandardCharsets.UTF_8);
    }

    private static StoredDocument readFrom(Path directory) throws Exception {
        Path content = directory.resolve("content.txt");
        if (!Files.exists(content) || Files.size(content) == 0) return null;
        Path header = directory.resolve("header.txt");
        return new StoredDocument(Files.exists(header) ? Files.readString(header) : "", Files.readString(content));
    }

    private static List<String> parseUrls(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        List<String> urls = new ArrayList<>();
        for (String value : raw.split(",")) if (!value.isBlank()) urls.add(withoutTrailingSlash(value.trim()));
        return List.copyOf(urls);
    }

    private static int parseInt(String name, int fallback) {
        try { return Integer.parseInt(System.getenv().getOrDefault(name, String.valueOf(fallback))); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private static String withoutTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    public record StoredDocument(String header, String content) {}

    public record CrawlResult(int bookId, String header, String content,
                              List<String> locations, boolean replicationSatisfied) {
        public String contentHash() {
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(content.getBytes(StandardCharsets.UTF_8)));
            } catch (Exception e) {
                throw new IllegalStateException("Cannot hash crawled content", e);
            }
        }
    }
}
