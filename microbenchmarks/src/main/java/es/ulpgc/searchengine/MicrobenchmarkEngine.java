package es.ulpgc.searchengine;

import es.ulpgc.searchengine.hazelcast.HazelcastIndex;
import es.ulpgc.searchengine.repository.DatamartMongo;

import java.util.*;

public class MicrobenchmarkEngine {
    private final DatamartMongo repo;
    private final HazelcastIndex hz;

    public MicrobenchmarkEngine(DatamartMongo repo, HazelcastIndex hz) {
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

    public Map<String,Object> runInsert(String content) {
        int id = new Random().nextInt(1000000);
        long t0 = System.nanoTime();
        repo.insertBook(id, "Test", "Author", "ES", 2025, content, "");
        long dbTime = System.nanoTime() - t0;

        t0 = System.nanoTime();
        hz.indexBook(Map.of("book_id", id, "content", content));
        long hzTime = System.nanoTime() - t0;

        return Map.of("book_id", id, "hazelcast_insert_ns", hzTime, "mongo_insert_ns", dbTime);
    }

    public Map<String,Object> runDelete(int id) {
        long t0 = System.nanoTime();
        repo.deleteBook(id);
        long dbTime = System.nanoTime() - t0;

        t0 = System.nanoTime();
        hz.deleteBook(id);
        long hzTime = System.nanoTime() - t0;

        return Map.of("book_id", id, "hazelcast_delete_ns", hzTime, "mongo_delete_ns", dbTime);
    }

    public Map<String,Object> getStats() {
        return Map.of("books", repo.countBooks(), "hz_connected", hz.isConnected(), "status", "ok");
    }
}
