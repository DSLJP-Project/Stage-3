package es.ulpgc.searchengine.ingestion.utils;

import com.google.gson.Gson;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

public class ReplicaClient {

    private final Gson gson;
    private final HttpClient http;

    public ReplicaClient(Gson gson) {
        this.gson = gson;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
    }

    public boolean sendReplica(String peerBaseUrl, String bookId, String content) {
        try {
            String url = peerBaseUrl.endsWith("/")
                    ? peerBaseUrl + "replica/ingest"
                    : peerBaseUrl + "/replica/ingest";

            String json = gson.toJson(Map.of(
                    "bookId", bookId,
                    "content", content
            ));

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(4))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() >= 200 && resp.statusCode() < 300;
        } catch (Exception e) {
            System.err.println("[ReplicaClient] failed to replicate to " + peerBaseUrl + " -> " + e.getMessage());
            return false;
        }
    }
}
