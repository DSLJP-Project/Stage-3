package es.ulpgc.searchengine.search;

import io.javalin.Javalin;
import io.javalin.http.Context;
import es.ulpgc.searchengine.search.repository.DatamartMongo;
import es.ulpgc.searchengine.search.hazelcast.HazelcastIndex;

import java.util.*;
import java.util.stream.Collectors;

public class SearchController {

    private final DatamartMongo repo;
    private final HazelcastIndex hz;

    public SearchController(DatamartMongo repo, HazelcastIndex hz) {
        this.repo = repo;
        this.hz   = hz;
    }

    public void register(Javalin app) {
        app.get("/search",          this::basicSearch);
        app.get("/search/phrase",   this::phraseSearch);
        app.get("/search/advanced", this::advancedSearch);
        app.get("/search/range",    this::rangeSearch);
        app.get("/status",  ctx -> ctx.json(Map.of("service", "search", "status", "running")));
        app.get("/health",  ctx -> ctx.status(200).result("OK"));
        app.get("/ready",   ctx -> {
            boolean hzOk = hz.isConnected();
            boolean dbOk = repo.testConnection();
            ctx.status(hzOk && dbOk ? 200 : 503)
                    .json(Map.of("hazelcast", hzOk, "db", dbOk));
        });
    }

    // ── GET /search?q=term[&author=X][&language=Y][&year=YYYY] ───────────────
    private void basicSearch(Context ctx) {
        String q = ctx.queryParam("q");
        if (q == null || q.isBlank()) {
            ctx.status(400).json(Map.of("error", "missing parameter: q"));
            return;
        }

        List<Integer> ids = hz.searchTerm(q);
        List<Map<String, Object>> books = repo.getBooksByIds(ids);

        Map<String, Object> filters = new LinkedHashMap<>();

        // Filtro por author
        String author = ctx.queryParam("author");
        if (author != null && !author.isBlank()) {
            filters.put("author", author);
            final String a = author;
            books = books.stream()
                    .filter(b -> a.equalsIgnoreCase((String) b.getOrDefault("author", "")))
                    .collect(Collectors.toList());
        }

        // Filtro por language
        String language = ctx.queryParam("language");
        if (language != null && !language.isBlank()) {
            filters.put("language", language);
            final String l = language;
            books = books.stream()
                    .filter(b -> l.equalsIgnoreCase((String) b.getOrDefault("language", "")))
                    .collect(Collectors.toList());
        }

        // Filtro por year
        String yearStr = ctx.queryParam("year");
        if (yearStr != null && !yearStr.isBlank()) {
            try {
                int year = Integer.parseInt(yearStr);
                filters.put("year", year);
                books = books.stream()
                        .filter(b -> year == (int) b.getOrDefault("year", -1))
                        .collect(Collectors.toList());
            } catch (NumberFormatException e) {
                ctx.status(400).json(Map.of("error", "invalid year parameter"));
                return;
            }
        }

        ctx.json(buildResponse(q, filters, books));
    }

    // ── GET /search/phrase?phrase=... ────────────────────────────────────────
    private void phraseSearch(Context ctx) {
        String phrase = ctx.queryParam("phrase");
        if (phrase == null || phrase.isBlank()) {
            ctx.status(400).json(Map.of("error", "missing parameter: phrase"));
            return;
        }

        String[] tokens = phrase.toLowerCase().split("\\s+");
        if (tokens.length == 0) {
            ctx.json(buildResponse(phrase, Map.of(), List.of()));
            return;
        }

        // 1) Candidatos por primer término via Hazelcast
        List<Integer> candidateIds = hz.searchTerm(tokens[0]);
        if (candidateIds.isEmpty()) {
            ctx.json(buildResponse(phrase, Map.of(), List.of()));
            return;
        }

        // 2) Filtrado exacto de frase en contenido de MongoDB
        List<Map<String, Object>> candidates = repo.getBooksByIds(candidateIds);
        String needle = phrase.toLowerCase();

        List<Map<String, Object>> matches = candidates.stream()
                .filter(b -> {
                    String content = (String) b.get("content");
                    return content != null && content.toLowerCase().contains(needle);
                })
                .collect(Collectors.toList());

        ctx.json(buildResponse(phrase, Map.of(), matches));
    }

    // ── GET /search/advanced?q=term1 AND|OR term2 ────────────────────────────
    private void advancedSearch(Context ctx) {
        String q = ctx.queryParam("q");
        if (q == null || q.isBlank()) {
            ctx.status(400).json(Map.of("error", "missing parameter: q"));
            return;
        }

        List<Integer> ids = hz.searchAdvanced(q);
        List<Map<String, Object>> books = repo.getBooksByIds(ids);
        ctx.json(buildResponse(q, Map.of(), books));
    }

    // ── GET /search/range?q=term&start_year=YYYY&end_year=YYYY ───────────────
    private void rangeSearch(Context ctx) {
        String q = ctx.queryParam("q");
        if (q == null || q.isBlank()) {
            ctx.status(400).json(Map.of("error", "missing parameter: q"));
            return;
        }

        int startYear, endYear;
        try {
            String sv = ctx.queryParam("start_year");
            String ev = ctx.queryParam("end_year");
            startYear = (sv != null && !sv.isBlank()) ? Integer.parseInt(sv) : 0;
            endYear   = (ev != null && !ev.isBlank()) ? Integer.parseInt(ev) : 9999;
        } catch (NumberFormatException e) {
            ctx.status(400).json(Map.of("error", "invalid year parameter: must be an integer"));
            return;
        }

        List<Integer> ids = hz.searchRange(q, startYear, endYear, repo);
        List<Map<String, Object>> books = repo.getBooksByIds(ids);
        Map<String, Object> filters = Map.of("start_year", startYear, "end_year", endYear);
        ctx.json(buildResponse(q, filters, books));
    }

    // ─── Helper: construye la respuesta con el formato exacto del spec ────────
    private Map<String, Object> buildResponse(String query,
                                              Map<String, Object> filters,
                                              List<Map<String, Object>> books) {
        // Proyectar solo los campos del spec (sin "content" que puede ser enorme)
        List<Map<String, Object>> results = books.stream()
                .map(b -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("book_id",  b.get("book_id"));
                    r.put("title",    b.getOrDefault("title",    "Unknown"));
                    r.put("author",   b.getOrDefault("author",   "Unknown"));
                    r.put("language", b.getOrDefault("language", "en"));
                    r.put("year",     b.getOrDefault("year",     0));
                    return r;
                })
                .collect(Collectors.toList());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("query",   query);
        response.put("filters", filters);
        response.put("count",   results.size());
        response.put("results", results);
        return response;
    }
}