package es.ulpgc.searchengine.benchmarks.engine;

import es.ulpgc.searchengine.benchmarks.repository.DatamartMongo;
import es.ulpgc.searchengine.benchmarks.hazelcast.HazelcastIndex;

import java.util.*;

public class BenchmarkEngine {
    private final DatamartMongo repo;
    private final HazelcastIndex hz;

    public BenchmarkEngine(DatamartMongo repo, HazelcastIndex hz) {
        this.repo = repo;
        this.hz = hz;
    }

    public Map<String,Object> runBasic(String term) {
        long t0 = System.nanoTime();
        List<Integer> hzRes = hz.searchTerm(term);
        long hzTime = System.nanoTime() - t0;

        t0 = System.nanoTime();
        List<Map<String,Object>> dbRes = repo.searchTerm(term);
        long dbTime = System.nanoTime() - t0;

        return Map.of("term", term, "hazelcast_time_ns", hzTime, "mongo_time_ns", dbTime,
                "hazelcast_results", hzRes.size(), "mongo_results", dbRes.size());
    }

    public Map<String,Object> runPhrase(String phrase) {
        long t0 = System.nanoTime();
        List<Integer> hzRes = hz.searchPhrase(phrase);
        long hzTime = System.nanoTime() - t0;

        t0 = System.nanoTime();
        List<Map<String,Object>> dbRes = repo.searchPhrase(phrase);
        long dbTime = System.nanoTime() - t0;

        return Map.of("phrase", phrase, "hazelcast_time_ns", hzTime, "mongo_time_ns", dbTime,
                "hazelcast_results", hzRes.size(), "mongo_results", dbRes.size());
    }

    public Map<String,Object> runBoolean(String query) {
        long t0 = System.nanoTime();
        List<Integer> hzRes = hz.searchAdvanced(query);
        long hzTime = System.nanoTime() - t0;

        t0 = System.nanoTime();
        List<Map<String,Object>> dbRes = repo.searchBoolean(query);
        long dbTime = System.nanoTime() - t0;

        return Map.of("query", query, "hazelcast_time_ns", hzTime, "mongo_time_ns", dbTime,
                "hazelcast_results", hzRes.size(), "mongo_results", dbRes.size());
    }

    public Map<String,Object> runRange(String term, int start, int end) {
        long t0 = System.nanoTime();
        List<Integer> hzRes = hz.searchRange(term, start, end, repo);
        long hzTime = System.nanoTime() - t0;

        t0 = System.nanoTime();
        List<Map<String,Object>> dbRes = repo.queryByYearRange(start, end);
        long dbTime = System.nanoTime() - t0;

        return Map.of("term", term, "start_year", start, "end_year", end,
                "hazelcast_time_ns", hzTime, "mongo_time_ns", dbTime,
                "hazelcast_results", hzRes.size(), "mongo_results", dbRes.size());
    }

    public Map<String,Object> getStats() {
        return Map.of("books", repo.countBooks(), "hz_connected", hz.isConnected(), "status", "ok");
    }
}
