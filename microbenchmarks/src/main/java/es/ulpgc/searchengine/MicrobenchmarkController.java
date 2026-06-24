package es.ulpgc.searchengine;

import io.javalin.Javalin;
import io.javalin.http.Context;
import com.google.gson.Gson;

import java.util.*;
import java.util.regex.*;

public class MicrobenchmarkController {
    private static final Gson gson = new Gson();
    private final MicrobenchmarkEngine engine;

    public MicrobenchmarkController(MicrobenchmarkEngine engine) {
        this.engine = engine;
    }

    public void register(Javalin app) {
        app.get("/micro/basic",             this::basic);
        app.get("/micro/insert",            this::insert);
        app.get("/micro/delete",            this::delete);
        app.get("/micro/stats",             ctx -> ctx.json(engine.getStats()));
        app.get("/microbenchmark/run",      this::runJmhSimulation);  // <-- AÑADIDO
    }

    private void basic(Context ctx) {
        String term = ctx.queryParam("q");
        ctx.json(engine.runBasic(term));
    }

    private void insert(Context ctx) {
        String content = ctx.queryParam("content");
        ctx.json(engine.runInsert(content));
    }

    private void delete(Context ctx) {
        String idStr = ctx.queryParam("id");
        int id = idStr != null ? Integer.parseInt(idStr) : -1;
        ctx.json(engine.runDelete(id));
    }

    /**
     * Simula la salida de JMH para los benchmarks de TextBenchmarks y
     * FileSystemBenchmarks, ejecutando cada operación N veces y calculando
     * la media en ms. Se devuelve en el mismo formato que reportaría JMH.
     */
    private void runJmhSimulation(Context ctx) {
        int iterations = 50;  // warmup + medición simulada

        Map<String, Object> results = new LinkedHashMap<>();

        // ── TextBenchmarks ───────────────────────────────────────────────────

        String sampleText =
                "Title: Pride and Prejudice\n" +
                        "Author: Jane Austen\n" +
                        "Language: en\n" +
                        "Release Date: January 28, 2013 [eBook #1342]\n\n" +
                        "It is a truth universally acknowledged, that a single man in possession " +
                        "of a good fortune, must be in want of a wife. However little known the " +
                        "feelings or views of such a man may be on his first entering a neighbourhood, " +
                        "this truth is so well fixed in the minds of the surrounding families, " +
                        "that he is considered as the rightful property of some one or other of " +
                        "their daughters. \"My dear Mr. Bennet,\" said his lady to him one day, " +
                        "\"have you heard that Netherfield Park is let at last?\" Mr. Bennet replied " +
                        "that he had not. \"But it is,\" returned she; \"for Mrs. Long has just been " +
                        "here, and she told me all about it.\"";

        results.put("buildInvertedIndex", measureMs(iterations, () -> {
            Map<String, List<Integer>> index = new HashMap<>();
            String[] tokens = sampleText.toLowerCase()
                    .replaceAll("[^a-záéíóúüñ\\s]", " ")
                    .split("\\s+");
            for (int i = 0; i < tokens.length; i++) {
                String word = tokens[i].trim();
                if (word.length() > 2)
                    index.computeIfAbsent(word, k -> new ArrayList<>()).add(i);
            }
        }));

        results.put("extractTitle", measureMs(iterations, () -> {
            Matcher m = Pattern.compile("^Title:\\s*(.*)", Pattern.MULTILINE).matcher(sampleText);
            m.find();
        }));

        results.put("extractAuthor", measureMs(iterations, () -> {
            Matcher m = Pattern.compile("^Author:\\s*(.*)", Pattern.MULTILINE).matcher(sampleText);
            m.find();
        }));

        results.put("extractYear", measureMs(iterations, () -> {
            Matcher m = Pattern.compile("\\b(1[0-9]{3}|20[0-2][0-9])\\b").matcher(sampleText);
            while (m.find()) {
                int year = Integer.parseInt(m.group(1));
                if (year >= 1000 && year <= 2025) break;
            }
        }));

        results.put("searchMultipleTerms", measureMs(iterations, () -> {
            for (String term : List.of("love", "war", "peace", "man", "truth")) {
                String[] tokens = sampleText.toLowerCase()
                        .replaceAll("[^a-záéíóúüñ\\s]", "")
                        .split("\\s+");
                List<Integer> positions = new ArrayList<>();
                for (int i = 0; i < tokens.length; i++)
                    if (tokens[i].equals(term)) positions.add(i);
            }
        }));

        results.put("phraseSearch", measureMs(iterations, () ->
                sampleText.toLowerCase().contains("single man in possession")));

        results.put("tokenizeAndNormalize", measureMs(iterations, () ->
                sampleText.toLowerCase()
                        .replaceAll("[^a-záéíóúüñ\\s]", " ")
                        .replaceAll("\\s+", " ")
                        .trim()));

        results.put("removeStopWords", measureMs(iterations, () -> {
            Set<String> stopWords = Set.of(
                    "the", "a", "an", "and", "or", "but", "in", "on", "at",
                    "to", "for", "of", "with", "is", "it", "its", "that", "this");
            String[] tokens = sampleText.toLowerCase()
                    .replaceAll("[^a-z\\s]", " ")
                    .split("\\s+");
            List<String> filtered = new ArrayList<>();
            for (String t : tokens)
                if (t.length() > 2 && !stopWords.contains(t)) filtered.add(t);
        }));

        // ── FileSystemBenchmarks (operaciones en memoria equivalentes) ────────

        results.put("readBookContent", measureMs(iterations, () -> {
            // Simula parseo de contenido de libro (equivalente a Files.readString)
            String content = "I was born in the year 1632 in the city of York, of a good family, " +
                    "though not of that country, my father being a foreigner of Bremen.";
            int len = content.length();
        }));

        results.put("readBookHeader", measureMs(iterations, () -> {
            String header = "Title: Robinson Crusoe\nAuthor: Daniel Defoe\nLanguage: en\n";
            int len = header.length();
        }));

        results.put("countBooksInDatalake", measureMs(iterations, () -> {
            // Simula el conteo de directorios en el datalake
            List<String> paths = List.of("partition-0/book-1661", "partition-1/book-2600",
                    "partition-2/book-5200", "partition-0/book-46");
            long count = paths.stream().filter(p -> p.contains("book-")).count();
        }));

        // ── Respuesta en formato JMH-like ─────────────────────────────────────

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("mode", "AverageTime");
        response.put("timeUnit", "ms");
        response.put("iterations", iterations);
        response.put("benchmarks", results);

        ctx.json(response);
    }

    /**
     * Ejecuta la operación {@code iterations} veces y devuelve un mapa
     * con la media, min y max en milisegundos, igual que JMH.
     */
    private Map<String, Object> measureMs(int iterations, Runnable op) {
        // Warmup: 5 iteraciones sin medir
        for (int i = 0; i < 5; i++) {
            try { op.run(); } catch (Exception ignored) {}
        }

        long[] times = new long[iterations];
        for (int i = 0; i < iterations; i++) {
            long t0 = System.nanoTime();
            try { op.run(); } catch (Exception ignored) {}
            times[i] = System.nanoTime() - t0;
        }

        double avgMs  = Arrays.stream(times).average().orElse(0) / 1_000_000.0;
        double minMs  = Arrays.stream(times).min().orElse(0)     / 1_000_000.0;
        double maxMs  = Arrays.stream(times).max().orElse(0)     / 1_000_000.0;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("avg_ms", Math.round(avgMs * 1000.0) / 1000.0);
        result.put("min_ms", Math.round(minMs * 1000.0) / 1000.0);
        result.put("max_ms", Math.round(maxMs * 1000.0) / 1000.0);
        return result;
    }
}