package es.ulpgc.searchengine.benchmarks;

import es.ulpgc.searchengine.benchmarks.utils.HttpHelper;
import com.google.gson.Gson;

import java.io.BufferedWriter;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.Collectors;

/**
 * BenchmarkRunner — implementa las 4 fases de benchmarking requeridas por Stage 3:
 *
 *  FASE 1 – Baseline:          pipeline secuencial (ingest → index → search) con métricas por libro.
 *  FASE 2 – Scaling test:      búsquedas concurrentes a 1/3/6/9 hilos para simular escalado horizontal.
 *  FASE 3 – Load test:         búsquedas concurrentes durante N segundos, midiendo p50/p95/p99.
 *  FASE 4 – Failure observation: queries continuas durante un período; detecta cortes y recuperación.
 */
public class BenchmarkRunner {

    private static final List<Integer> BENCHMARK_BOOKS =
            List.of(46, 60, 61, 98, 1567, 1661, 2600, 5200);

    private static final List<String> SEARCH_TERMS =
            List.of("the", "love", "war", "sea", "man", "night", "adventure", "heart");

    private final List<String> crawlerUrls;
    private final String indexingUrl;
    private final String searchUrl;
    private final Gson   gson = new Gson();

    public BenchmarkRunner(String crawlerUrls, String indexingUrl, String searchUrl) {
        this.crawlerUrls = parseUrls(crawlerUrls);
        this.indexingUrl  = indexingUrl;
        this.searchUrl    = searchUrl;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ENTRADA PRINCIPAL
    // ═══════════════════════════════════════════════════════════════════════════

    public void runFullBenchmark() throws Exception {
        System.out.println("\n╔══════════════════════════════════════╗");
        System.out.println("║       FULL BENCHMARK — Stage 3       ║");
        System.out.println("╚══════════════════════════════════════╝");

        runBaselineBenchmark();
        runScalingBenchmark();
        runLoadTest(6, 30);
        runFailureObservation(30);

        System.out.println("\n╔══════════════════════════════════════╗");
        System.out.println("║       BENCHMARK COMPLETE             ║");
        System.out.println("║  Results saved in /results/          ║");
        System.out.println("╚══════════════════════════════════════╝");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // FASE 1 — BASELINE
    // ═══════════════════════════════════════════════════════════════════════════

    public Map<String, Object> runBaselineBenchmark() throws Exception {
        System.out.println("\n━━━ PHASE 1: BASELINE ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        List<BenchmarkResult> results = new ArrayList<>();

        for (int bookId : BENCHMARK_BOOKS) {
            System.out.printf("%n── Book %d ─────────────────────────────────────%n", bookId);
            results.add(runSinglePipeline(bookId));
        }

        Map<String, Object> stats = computeBaselineStats(results);
        saveBaselineResults(results, stats);

        System.out.printf("%n[Baseline] SUCCESS: %s/%d | avg ingest=%.0fms index=%.0fms search=%.0fms%n",
                stats.get("books_success"), results.size(),
                toDouble(stats.get("avg_ingestion_ms")),
                toDouble(stats.get("avg_indexing_ms")),
                toDouble(stats.get("avg_search_ms")));
        return stats;
    }

    private BenchmarkResult runSinglePipeline(int bookId) {
        BenchmarkResult r = new BenchmarkResult(bookId);
        Instant t0 = Instant.now();

        try {
            // ── Ingestion + réplica en el crawler propietario ───────────────
            Instant ti = Instant.now();
            String crawlerUrl = crawlerUrls.get(Math.floorMod(bookId, crawlerUrls.size()));
            int ic = HttpHelper.post(crawlerUrl + "/crawl/" + bookId);
            r.ingestionTime = Duration.between(ti, Instant.now()).toMillis();
            if (ic != 202) throw new RuntimeException("Crawler HTTP " + ic);

            // ── Indexing asíncrono: esperar confirmación del consumidor JMS ─
            Instant idx = Instant.now();
            waitForIndex(bookId);
            r.indexingTime = Duration.between(idx, Instant.now()).toMillis();

            // ── Search (3 tipos) ───────────────────────────────────────────
            Instant ts = Instant.now();
            int s1 = HttpHelper.get(searchUrl + "/search?q=the");
            int s2 = HttpHelper.get(searchUrl + "/search/phrase?phrase=the+end");
            int s3 = HttpHelper.get(searchUrl + "/search/advanced?q=the+AND+end");
            r.searchTime = Duration.between(ts, Instant.now()).toMillis();
            if (s1 != 200 || s2 != 200 || s3 != 200)
                throw new RuntimeException("Search HTTP " + s1 + "/" + s2 + "/" + s3);

            r.success = true;
            System.out.printf("  ✓ Book %d — ingest=%dms  index=%dms  search=%dms%n",
                    bookId, r.ingestionTime, r.indexingTime, r.searchTime);

        } catch (Exception e) {
            r.success = false;
            System.err.printf("  ✗ Book %d FAILED: %s%n", bookId, e.getMessage());
        }

        r.totalTime = Duration.between(t0, Instant.now()).toMillis();
        return r;
    }

    private void waitForIndex(int bookId) throws Exception {
        long deadline = System.currentTimeMillis() + 120_000;
        while (System.currentTimeMillis() < deadline) {
            if (HttpHelper.get(indexingUrl + "/index/status/" + bookId) == 200) return;
            Thread.sleep(500);
        }
        throw new RuntimeException("Timed out waiting for asynchronous indexing of book " + bookId);
    }

    private static List<String> parseUrls(String raw) {
        List<String> urls = new ArrayList<>();
        for (String value : raw.split(",")) if (!value.isBlank()) urls.add(value.trim().replaceAll("/$", ""));
        if (urls.isEmpty()) throw new IllegalArgumentException("CRAWLER_URLS must contain at least one URL");
        return List.copyOf(urls);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // FASE 2 — SCALING TEST
    // ═══════════════════════════════════════════════════════════════════════════

    public Map<String, Object> runScalingBenchmark() throws Exception {
        System.out.println("\n━━━ PHASE 2: SCALING TEST ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("Simulating horizontal scale by increasing concurrent search clients.");
        System.out.println("(In a real multi-node test, add nodes between runs.)");

        Map<String, Object> allResults = new LinkedHashMap<>();

        for (int threads : new int[]{1, 3, 6, 9}) {
            System.out.printf("%n  → Concurrency level: %d threads (20s)%n", threads);
            Map<String, Object> result = runConcurrentSearch(threads, 20);
            allResults.put("concurrency_" + threads, result);
            System.out.printf("    rps=%.1f  p50=%.0fms  p95=%.0fms  p99=%.0fms  errors=%s%n",
                    toDouble(result.get("throughput_rps")),
                    toDouble(result.get("p50_ms")),
                    toDouble(result.get("p95_ms")),
                    toDouble(result.get("p99_ms")),
                    result.get("failed"));
        }

        saveScalingResults(allResults);
        return allResults;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // FASE 3 — LOAD TEST
    // ═══════════════════════════════════════════════════════════════════════════

    public Map<String, Object> runLoadTest(int threads, int durationSeconds) throws Exception {
        System.out.printf("%n━━━ PHASE 3: LOAD TEST (%d threads, %ds) ━━━━━━━━━━━━━━━━━━━━━━━%n",
                threads, durationSeconds);

        Map<String, Object> result = runConcurrentSearch(threads, durationSeconds);

        System.out.printf("  total=%s  success=%s  failed=%s%n",
                result.get("total_requests"), result.get("successful"), result.get("failed"));
        System.out.printf("  rps=%.1f  avg=%.0fms  p50=%.0fms  p95=%.0fms  p99=%.0fms  max=%.0fms%n",
                toDouble(result.get("throughput_rps")),
                toDouble(result.get("avg_ms")),
                toDouble(result.get("p50_ms")),
                toDouble(result.get("p95_ms")),
                toDouble(result.get("p99_ms")),
                toDouble(result.get("max_ms")));

        saveLoadTestResults(result, threads, durationSeconds);
        return result;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // FASE 4 — FAILURE OBSERVATION
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Envía queries continuas durante observationSeconds.
     * Si en ese período alguien mata un nodo, este código detecta los errores,
     * el tiempo de recuperación y la disponibilidad final del sistema.
     */
    public Map<String, Object> runFailureObservation(int observationSeconds) throws Exception {
        System.out.printf("%n━━━ PHASE 4: FAILURE OBSERVATION (%ds) ━━━━━━━━━━━━━━━━━━━━━━━━%n",
                observationSeconds);
        System.out.println("  Sending continuous queries. Kill a search node NOW to observe recovery.");

        List<Long>   latencies    = Collections.synchronizedList(new ArrayList<>());
        List<String> errorLog     = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger success     = new AtomicInteger();
        AtomicInteger failures    = new AtomicInteger();
        AtomicLong   firstError   = new AtomicLong(-1);
        AtomicLong   firstRecovery = new AtomicLong(-1);

        Random rnd = new Random();
        long   endMs = System.currentTimeMillis() + (observationSeconds * 1000L);
        long   startMs = System.currentTimeMillis();

        while (System.currentTimeMillis() < endMs) {
            String term = SEARCH_TERMS.get(rnd.nextInt(SEARCH_TERMS.size()));
            long t0 = System.currentTimeMillis();
            try {
                int code = HttpHelper.get(searchUrl + "/search?q=" + term);
                long ms = System.currentTimeMillis() - t0;
                if (code == 200) {
                    success.incrementAndGet();
                    latencies.add(ms);
                    // Detectar recuperación tras error
                    if (firstError.get() > 0 && firstRecovery.get() < 0)
                        firstRecovery.set(System.currentTimeMillis());
                } else {
                    failures.incrementAndGet();
                    if (firstError.get() < 0) firstError.set(System.currentTimeMillis());
                    String entry = String.format("HTTP %d at T+%.1fs",
                            code, (System.currentTimeMillis() - startMs) / 1000.0);
                    errorLog.add(entry);
                    System.err.printf("  [Failure] %s%n", entry);
                }
            } catch (Exception e) {
                failures.incrementAndGet();
                if (firstError.get() < 0) firstError.set(System.currentTimeMillis());
                String entry = String.format("%s at T+%.1fs",
                        e.getMessage(), (System.currentTimeMillis() - startMs) / 1000.0);
                errorLog.add(entry);
                System.err.printf("  [Failure] %s%n", entry);
            }
            Thread.sleep(200); // ~5 req/s
        }

        int total = success.get() + failures.get();
        double availability = 100.0 * success.get() / Math.max(1, total);

        long recoveryTimeMs = (firstError.get() > 0 && firstRecovery.get() > 0)
                ? firstRecovery.get() - firstError.get() : -1;

        List<Long> sorted = new ArrayList<>(latencies);
        Collections.sort(sorted);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("observation_seconds",  observationSeconds);
        result.put("total_requests",       total);
        result.put("successful_requests",  success.get());
        result.put("failed_requests",      failures.get());
        result.put("availability_percent", Math.round(availability * 10.0) / 10.0);
        result.put("recovery_time_ms",     recoveryTimeMs);
        result.put("p50_ms",  sorted.isEmpty() ? 0 : percentile(sorted, 50));
        result.put("p95_ms",  sorted.isEmpty() ? 0 : percentile(sorted, 95));
        result.put("max_ms",  sorted.isEmpty() ? 0 : sorted.get(sorted.size() - 1));
        result.put("error_log", errorLog);

        System.out.printf("  availability=%.1f%%  success=%d  failed=%d  recovery=%s ms%n",
                availability, success.get(), failures.get(),
                recoveryTimeMs >= 0 ? recoveryTimeMs : "N/A (no failure detected)");
        System.out.printf("  p50=%.0fms  p95=%.0fms  max=%.0fms%n",
                toDouble(result.get("p50_ms")),
                toDouble(result.get("p95_ms")),
                toDouble(result.get("max_ms")));

        saveFailureResults(result);
        return result;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CONCURRENT SEARCH ENGINE (compartido por Fase 2 y 3)
    // ═══════════════════════════════════════════════════════════════════════════

    private Map<String, Object> runConcurrentSearch(int threads, int durationSeconds)
            throws Exception {

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Long>   latencies = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger success  = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();

        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go    = new CountDownLatch(1);
        long endMs = System.currentTimeMillis() + (durationSeconds * 1000L);

        for (int i = 0; i < threads; i++) {
            final String term = SEARCH_TERMS.get(i % SEARCH_TERMS.size());
            pool.submit(() -> {
                ready.countDown();
                try { go.await(); } catch (InterruptedException ex) { return; }

                while (System.currentTimeMillis() < endMs) {
                    long t0 = System.currentTimeMillis();
                    try {
                        int code = HttpHelper.get(searchUrl + "/search?q=" + term);
                        long ms = System.currentTimeMillis() - t0;
                        if (code == 200) { success.incrementAndGet(); latencies.add(ms); }
                        else               failures.incrementAndGet();
                    } catch (Exception e) {
                        failures.incrementAndGet();
                    }
                }
            });
        }

        ready.await();
        long startMs = System.currentTimeMillis();
        go.countDown();
        pool.shutdown();
        pool.awaitTermination(durationSeconds + 15L, TimeUnit.SECONDS);

        long elapsed = System.currentTimeMillis() - startMs;
        int  total   = success.get() + failures.get();
        double rps   = 1000.0 * total / Math.max(1, elapsed);

        List<Long> sorted = new ArrayList<>(latencies);
        Collections.sort(sorted);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("threads",          threads);
        result.put("duration_seconds", durationSeconds);
        result.put("total_requests",   total);
        result.put("successful",       success.get());
        result.put("failed",           failures.get());
        result.put("errors",           failures.get());  // alias esperado por el txt de pruebas
        result.put("throughput_rps",   Math.round(rps * 10.0) / 10.0);
        result.put("avg_ms",   sorted.isEmpty() ? 0 : sorted.stream().mapToLong(v -> v).average().orElse(0));
        result.put("p50_ms",   sorted.isEmpty() ? 0 : percentile(sorted, 50));
        result.put("p95_ms",   sorted.isEmpty() ? 0 : percentile(sorted, 95));
        result.put("p99_ms",   sorted.isEmpty() ? 0 : percentile(sorted, 99));
        result.put("max_ms",   sorted.isEmpty() ? 0 : sorted.get(sorted.size() - 1));
        return result;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PERSISTENCIA DE RESULTADOS
    // ═══════════════════════════════════════════════════════════════════════════

    private void saveBaselineResults(List<BenchmarkResult> results, Map<String, Object> stats)
            throws Exception {
        Path dir = Paths.get("results");
        Files.createDirectories(dir);

        Files.writeString(dir.resolve("baseline.json"), gson.toJson(Map.of(
                "phase", "baseline", "stats", stats, "details", results)));

        try (BufferedWriter w = Files.newBufferedWriter(dir.resolve("baseline.csv"))) {
            w.write("book_id,ingestion_ms,indexing_ms,search_ms,total_ms,success\n");
            for (BenchmarkResult r : results)
                w.write(String.format("%d,%d,%d,%d,%d,%b%n",
                        r.bookId, r.ingestionTime, r.indexingTime,
                        r.searchTime, r.totalTime, r.success));
        }
        System.out.println("  → Saved results/baseline.json and results/baseline.csv");
    }

    private void saveScalingResults(Map<String, Object> data) throws Exception {
        Path dir = Paths.get("results");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("scaling.json"), gson.toJson(data));

        try (BufferedWriter w = Files.newBufferedWriter(dir.resolve("scaling.csv"))) {
            w.write("concurrency,throughput_rps,avg_ms,p50_ms,p95_ms,p99_ms,max_ms,success,failed\n");
            for (Map.Entry<String, Object> e : data.entrySet()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> r = (Map<String, Object>) e.getValue();
                w.write(String.format("%s,%.1f,%.0f,%.0f,%.0f,%.0f,%.0f,%s,%s%n",
                        e.getKey(),
                        toDouble(r.get("throughput_rps")), toDouble(r.get("avg_ms")),
                        toDouble(r.get("p50_ms")),         toDouble(r.get("p95_ms")),
                        toDouble(r.get("p99_ms")),         toDouble(r.get("max_ms")),
                        r.get("successful"), r.get("failed")));
            }
        }
        System.out.println("  → Saved results/scaling.json and results/scaling.csv");
    }

    private void saveLoadTestResults(Map<String, Object> data, int threads, int duration)
            throws Exception {
        Path dir = Paths.get("results");
        Files.createDirectories(dir);
        String name = "loadtest_t" + threads + "_d" + duration;
        Files.writeString(dir.resolve(name + ".json"), gson.toJson(data));
        System.out.println("  → Saved results/" + name + ".json");
    }

    private void saveFailureResults(Map<String, Object> data) throws Exception {
        Path dir = Paths.get("results");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("failure_observation.json"), gson.toJson(data));
        System.out.println("  → Saved results/failure_observation.json");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    private Map<String, Object> computeBaselineStats(List<BenchmarkResult> results) {
        long ok = results.stream().filter(r -> r.success).count();
        return Map.of(
                "books_tested",     results.size(),
                "books_success",    ok,
                "avg_ingestion_ms", Math.round(results.stream().mapToLong(r -> r.ingestionTime).average().orElse(0)),
                "avg_indexing_ms",  Math.round(results.stream().mapToLong(r -> r.indexingTime).average().orElse(0)),
                "avg_search_ms",    Math.round(results.stream().mapToLong(r -> r.searchTime).average().orElse(0)),
                "avg_total_ms",     Math.round(results.stream().mapToLong(r -> r.totalTime).average().orElse(0))
        );
    }

    private long percentile(List<Long> sorted, int pct) {
        if (sorted.isEmpty()) return 0;
        int idx = (int) Math.ceil(pct / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }

    private double toDouble(Object val) {
        return (val instanceof Number n) ? n.doubleValue() : 0;
    }
}
