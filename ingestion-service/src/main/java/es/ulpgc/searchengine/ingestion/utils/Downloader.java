package es.ulpgc.searchengine.ingestion.utils;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;

public class Downloader {
    private static final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public static String get(String url) throws Exception {
        int maxRetries = Integer.parseInt(System.getenv().getOrDefault("DOWNLOAD_RETRIES", "3"));
        Duration timeout = Duration.ofSeconds(Long.parseLong(System.getenv().getOrDefault("DOWNLOAD_TIMEOUT_SEC", "15")));

        for (int i = 0; i < maxRetries; i++) {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(timeout)
                        .header("User-Agent", "ULPGC-SearchEngine/Stage3")
                        .GET()
                        .build();

                HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
                int code = res.statusCode();
                if (code == 200) return res.body();

                // Backoff en 429/5xx; error en 4xx
                if (code == 429 || code >= 500) {
                    Thread.sleep(1000L * (i + 1));
                    continue;
                } else {
                    throw new RuntimeException("Failed download: HTTP " + code);
                }

            } catch (IOException | InterruptedException e) {
                if (i == maxRetries - 1) throw e;
                Thread.sleep(1000L * (i + 1));
            }
        }
        throw new RuntimeException("Download retries exhausted");
    }
}
