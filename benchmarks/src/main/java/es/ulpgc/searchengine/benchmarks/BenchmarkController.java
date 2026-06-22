package es.ulpgc.searchengine.benchmarks;

import io.javalin.Javalin;
import io.javalin.http.Context;
import com.google.gson.Gson;
import es.ulpgc.searchengine.benchmarks.engine.BenchmarkEngine;

import java.util.Map;

public class BenchmarkController {
    private static final Gson gson = new Gson();
    private final BenchmarkEngine engine;
    private final BenchmarkRunner runner;

    public BenchmarkController(BenchmarkEngine engine, BenchmarkRunner runner) {
        this.engine = engine;
        this.runner = runner;
    }

    public void register(Javalin app) {
        // ── Micro-comparaciones Hazelcast vs MongoDB (existentes) ─────────
        app.get("/benchmark/basic",   this::basicBenchmark);
        app.get("/benchmark/phrase",  this::phraseBenchmark);
        app.get("/benchmark/boolean", this::booleanBenchmark);
        app.get("/benchmark/range",   this::rangeBenchmark);
        app.get("/benchmark/stats",   ctx -> ctx.json(engine.getStats()));

        // ── Las 4 fases de benchmarking exigidas por Stage 3 ──────────────
        // GET /benchmark/run/baseline
        // GET /benchmark/run/scaling
        // GET /benchmark/run/load?threads=6&seconds=30
        // GET /benchmark/run/failure?seconds=30
        // GET /benchmark/run/all   (ejecuta las 4 secuencialmente)
        app.get("/benchmark/run/{phase}", this::runPhase);
    }

    private void basicBenchmark(Context ctx) {
        String term = ctx.queryParam("q");
        ctx.json(engine.runBasic(term));
    }

    private void phraseBenchmark(Context ctx) {
        String phrase = ctx.queryParam("phrase");
        ctx.json(engine.runPhrase(phrase));
    }

    private void booleanBenchmark(Context ctx) {
        String query = ctx.queryParam("q");
        ctx.json(engine.runBoolean(query));
    }

    private void rangeBenchmark(Context ctx) {
        String term = ctx.queryParam("q");
        String startStr = ctx.queryParam("start_year");
        String endStr   = ctx.queryParam("end_year");
        int start = startStr != null ? Integer.parseInt(startStr) : 0;
        int end   = endStr   != null ? Integer.parseInt(endStr)   : 9999;
        ctx.json(engine.runRange(term, start, end));
    }

    /**
     * Ejecuta una fase del benchmark de Stage 3.
     * NOTA: estas llamadas son SÍNCRONAS y pueden tardar 20-60s.
     * Están pensadas para lanzarse manualmente (curl / Postman) o desde
     * el Control Module durante la demo, no bajo alta concurrencia.
     */
    private void runPhase(Context ctx) {
        String phase = ctx.pathParam("phase").toLowerCase();

        try {
            switch (phase) {
                case "baseline" -> ctx.json(Map.of(
                        "phase", "baseline",
                        "result", runner.runBaselineBenchmark()));

                case "scaling" -> ctx.json(Map.of(
                        "phase", "scaling",
                        "result", runner.runScalingBenchmark()));

                case "load" -> {
                    int threads = parseIntOr(ctx.queryParam("threads"), 6);
                    int seconds = parseIntOr(ctx.queryParam("seconds"), 30);
                    ctx.json(Map.of(
                            "phase", "load",
                            "threads", threads,
                            "seconds", seconds,
                            "result", runner.runLoadTest(threads, seconds)));
                }

                case "failure" -> {
                    int seconds = parseIntOr(ctx.queryParam("seconds"), 30);
                    ctx.json(Map.of(
                            "phase", "failure",
                            "seconds", seconds,
                            "result", runner.runFailureObservation(seconds)));
                }

                case "all" -> {
                    runner.runFullBenchmark();
                    ctx.json(Map.of("phase", "all", "status", "completed",
                            "note", "Results saved under /results/ inside the container"));
                }

                default -> ctx.status(400).json(Map.of(
                        "error", "unknown phase: " + phase,
                        "valid_phases", new String[]{"baseline", "scaling", "load", "failure", "all"}));
            }
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", e.getMessage()));
        }
    }

    private int parseIntOr(String val, int fallback) {
        if (val == null || val.isBlank()) return fallback;
        try { return Integer.parseInt(val); } catch (NumberFormatException e) { return fallback; }
    }
}