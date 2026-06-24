package es.ulpgc.searchengine.benchmarks.hazelcast;

import com.hazelcast.client.HazelcastClient;
import com.hazelcast.client.config.ClientConfig;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import es.ulpgc.searchengine.benchmarks.repository.DatamartMongo;

import java.util.*;

public class HazelcastIndex {

    private final HazelcastInstance hazelcast;
    private final IMap<String, List<Integer>> invertedIndex;

    public HazelcastIndex() {

        ClientConfig clientConfig = new ClientConfig();
        clientConfig.setClusterName("search-cluster");

        clientConfig.getNetworkConfig().addAddress(
                "hazelcast-1:5701",
                "hazelcast-2:5701",
                "hazelcast-3:5701"
        );

        this.hazelcast = HazelcastClient.newHazelcastClient(clientConfig);
        this.invertedIndex = hazelcast.getMap("inverted-index");
    }

    // ------------------------------------------------------------
    // BASIC SEARCH
    // ------------------------------------------------------------

    public List<Integer> searchTerm(String term) {
        if (term == null || term.isBlank()) return List.of();
        List<Integer> res = invertedIndex.get(term.toLowerCase());
        return res == null ? List.of() : new ArrayList<>(res);
    }

    // ------------------------------------------------------------
    // PHRASE SEARCH (Hazelcast → candidates only)
    // ------------------------------------------------------------

    public List<Integer> searchPhrase(String phrase) {
        if (phrase == null || phrase.isBlank()) return List.of();

        String[] tokens = phrase.toLowerCase().split("\\s+");
        if (tokens.length == 0) return List.of();

        return searchTerm(tokens[0]);
    }

    // ------------------------------------------------------------
    // BOOLEAN SEARCH (AND / OR)
    // ------------------------------------------------------------

    public List<Integer> searchAdvanced(String expr) {
        if (expr == null || expr.isBlank()) return List.of();

        String[] parts = expr.split("\\s+");
        if (parts.length < 3) return searchTerm(expr);

        String t1 = parts[0].toLowerCase();
        String op = parts[1].toUpperCase();
        String t2 = parts[2].toLowerCase();

        Set<Integer> s1 = new LinkedHashSet<>(searchTerm(t1));
        Set<Integer> s2 = new LinkedHashSet<>(searchTerm(t2));

        if ("AND".equals(op)) {
            s1.retainAll(s2);
        } else if ("OR".equals(op)) {
            s1.addAll(s2);
        }

        return new ArrayList<>(s1);
    }

    // ------------------------------------------------------------
    // RANGE SEARCH (Hazelcast → candidates, Mongo → filter)
    // ------------------------------------------------------------

    public List<Integer> searchRange(String term, int start, int end, DatamartMongo repo) {

        List<Integer> candidates = searchTerm(term);
        if (candidates.isEmpty()) return List.of();

        List<Map<String,Object>> books = repo.getBooksByIds(candidates);
        List<Integer> result = new ArrayList<>();

        for (Map<String,Object> book : books) {
            int year = (Integer) book.getOrDefault("year", -1);
            if (year >= start && year <= end) {
                result.add((Integer) book.get("book_id"));
            }
        }

        return result;
    }

    // ------------------------------------------------------------
    // HEALTH / LIFECYCLE
    // ------------------------------------------------------------

    public boolean isConnected() {
        return hazelcast != null &&
                hazelcast.getLifecycleService().isRunning();
    }

    public void close() {
        if (hazelcast != null) {
            hazelcast.shutdown();
        }
    }
}
