package es.ulpgc.searchengine.benchmarks.utils;

import java.net.http.*;
import java.net.URI;
import java.time.Duration;

public class HttpHelper {

    private static final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    public static int get(String url) throws Exception {
        System.out.println("Making GET request to: " + url);
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build();

            HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
            System.out.println("Response status: " + res.statusCode());
            return res.statusCode();

        } catch (Exception e) {
            System.err.println("GET request failed for: " + url);
            return -1;
        }
    }

    public static int post(String url) throws Exception {
        System.out.println("Making POST request to: " + url);
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    // Enviar cuerpo vacío JSON para evitar 404 en Javalin/Jetty
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .header("Content-Type", "application/json")
                    .build();

            HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
            System.out.println("Response status: " + res.statusCode());
            return res.statusCode();

        } catch (Exception e) {
            System.err.println("POST request failed for: " + url);
            return -1;
        }
    }
}
