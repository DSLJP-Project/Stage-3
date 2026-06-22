package es.ulpgc.searchengine.benchmarks.hazelcast;

import com.hazelcast.client.HazelcastClient;
import com.hazelcast.client.config.ClientConfig;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.multimap.MultiMap;
import es.ulpgc.searchengine.benchmarks.repository.DatamartMongo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reads the same three MultiMap shards used by the running search services. */
public class HazelcastIndex {

    private final HazelcastInstance hazelcast;

    public HazelcastIndex() {
        ClientConfig config = new ClientConfig();
        config.setClusterName(System.getenv().getOrDefault("HZ_CLUSTER_NAME", "search-cluster"));
        config.getNetworkConfig().addAddress("hazelcast-1:5701", "hazelcast-2:5701", "hazelcast-3:5701");
        hazelcast = HazelcastClient.newHazelcastClient(config);
    }

    public List<Integer> searchTerm(String term) {
        if (term == null || term.isBlank()) return List.of();
        String normalized = term.toLowerCase();
        Collection<Integer> postings = hazelcast.<String, Integer>getMultiMap(shardFor(normalized)).get(normalized);
        return postings == null ? List.of() : new ArrayList<>(new LinkedHashSet<>(postings));
    }

    public List<Integer> searchPhrase(String phrase) {
        if (phrase == null || phrase.isBlank()) return List.of();
        return searchTerm(phrase.toLowerCase().split("\\s+")[0]);
    }

    public List<Integer> searchAdvanced(String expression) {
        if (expression == null || expression.isBlank()) return List.of();
        String[] parts = expression.split("\\s+");
        if (parts.length < 3) return searchTerm(expression);
        Set<Integer> left = new LinkedHashSet<>(searchTerm(parts[0]));
        Set<Integer> right = new LinkedHashSet<>(searchTerm(parts[2]));
        if ("AND".equalsIgnoreCase(parts[1])) left.retainAll(right);
        else if ("OR".equalsIgnoreCase(parts[1])) left.addAll(right);
        return new ArrayList<>(left);
    }

    public List<Integer> searchRange(String term, int start, int end, DatamartMongo repo) {
        List<Integer> candidates = searchTerm(term);
        List<Integer> result = new ArrayList<>();
        for (Map<String, Object> book : repo.getBooksByIds(candidates)) {
            int year = (Integer) book.getOrDefault("year", -1);
            if (year >= start && year <= end) result.add((Integer) book.get("book_id"));
        }
        return result;
    }

    private static String shardFor(String term) {
        return "inverted_index_" + Math.floorMod(term.hashCode(), 3);
    }

    public boolean isConnected() {
        return hazelcast != null && hazelcast.getLifecycleService().isRunning();
    }

    public void close() {
        if (hazelcast != null) hazelcast.shutdown();
    }
}
