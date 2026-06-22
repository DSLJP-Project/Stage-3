package es.ulpgc.searchengine.search.hazelcast;

import com.hazelcast.client.HazelcastClient;
import com.hazelcast.client.config.ClientConfig;
import com.hazelcast.config.EvictionConfig;
import com.hazelcast.config.EvictionPolicy;
import com.hazelcast.config.NearCacheConfig;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.multimap.MultiMap;
import es.ulpgc.searchengine.search.repository.DatamartMongo;

import java.util.*;

public class HazelcastIndex {

    private final HazelcastInstance hz;

    public HazelcastIndex() {
        ClientConfig clientConfig = new ClientConfig();

        // Lee cluster name de variable de entorno (consistente con todos los demás servicios)
        String clusterName = System.getenv().getOrDefault("HZ_CLUSTER_NAME", "search-cluster");
        clientConfig.setClusterName(clusterName);

        clientConfig.getNetworkConfig()
                .setSmartRouting(true)
                .setRedoOperation(true)
                .addAddress("hazelcast-1:5701", "hazelcast-2:5701", "hazelcast-3:5701");

        clientConfig.getConnectionStrategyConfig()
                .getConnectionRetryConfig()
                .setClusterConnectTimeoutMillis(20000);

        // Near Cache LRU para lecturas repetidas (reduce latencia en búsquedas frecuentes)
        NearCacheConfig nearCache = new NearCacheConfig("inverted_index_*")
                .setInvalidateOnChange(true)
                .setEvictionConfig(new EvictionConfig()
                        .setEvictionPolicy(EvictionPolicy.LRU)
                        .setSize(10_000));
        clientConfig.addNearCacheConfig(nearCache);

        hz = HazelcastClient.newHazelcastClient(clientConfig);

        System.out.printf("[SearchHazelcastIndex] Connected to cluster '%s'. Members: %d%n",
                clusterName, hz.getCluster().getMembers().size());
    }

    public boolean isConnected() {
        return hz != null && hz.getLifecycleService().isRunning();
    }

    // ─── Sharding: mismo hash que el indexer ─────────────────────────────────
    private String shardFor(String term) {
        return "inverted_index_" + Math.floorMod(term.hashCode(), 3);
    }

    // ── Búsqueda simple ───────────────────────────────────────────────────────
    public List<Integer> searchTerm(String term) {
        if (term == null || term.isBlank()) return List.of();

        String normalized = term.toLowerCase();
        MultiMap<String, Integer> shard = hz.getMultiMap(shardFor(normalized));
        Collection<Integer> postings = shard.get(normalized);

        if (postings == null || postings.isEmpty()) return List.of();
        return new ArrayList<>(new LinkedHashSet<>(postings));   // dedup + preserva orden
    }

    // ── Búsqueda avanzada AND / OR ────────────────────────────────────────────
    public List<Integer> searchAdvanced(String query) {
        if (query == null || query.isBlank()) return List.of();

        String[] parts = query.split("\\s+");
        if (parts.length < 3) return searchTerm(query);

        String term1    = parts[0].toLowerCase();
        String operator = parts[1].toUpperCase();
        String term2    = parts[2].toLowerCase();

        Set<Integer> set1 = new LinkedHashSet<>(searchTerm(term1));
        Set<Integer> set2 = new LinkedHashSet<>(searchTerm(term2));

        if ("AND".equals(operator))       set1.retainAll(set2);
        else if ("OR".equals(operator))   set1.addAll(set2);

        return new ArrayList<>(set1);
    }

    // ── Búsqueda por rango de años ────────────────────────────────────────────
    public List<Integer> searchRange(String term, int startYear, int endYear, DatamartMongo repo) {
        List<Integer> candidates = searchTerm(term);
        if (candidates.isEmpty()) return List.of();

        List<Map<String, Object>> books = repo.getBooksByIds(candidates);
        List<Integer> filtered = new ArrayList<>();

        for (Map<String, Object> book : books) {
            int year = (int) book.getOrDefault("year", -1);
            if (year >= startYear && year <= endYear)
                filtered.add((Integer) book.get("book_id"));
        }
        return filtered;
    }

    public void close() {
        if (hz != null) {
            System.out.println("[SearchHazelcastIndex] Shutting down Hazelcast client...");
            hz.shutdown();
        }
    }
}
