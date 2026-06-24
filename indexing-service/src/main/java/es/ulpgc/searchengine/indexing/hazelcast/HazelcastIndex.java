package es.ulpgc.searchengine.indexing.hazelcast;

import com.hazelcast.client.HazelcastClient;
import com.hazelcast.client.config.ClientConfig;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import com.hazelcast.multimap.MultiMap;

import java.util.*;

public class HazelcastIndex {

    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "the", "and", "or", "but", "in", "on", "at", "to", "for",
            "of", "with", "by", "from", "as", "is", "was", "are", "were", "be",
            "been", "being", "have", "has", "had", "do", "does", "did", "will",
            "would", "could", "should", "may", "might", "shall", "can", "not",
            "no", "nor", "so", "yet", "both", "either", "neither", "than", "that",
            "this", "these", "those", "it", "its", "he", "she", "they", "we",
            "you", "i", "me", "him", "her", "us", "them", "my", "his", "our",
            "your", "their", "what", "which", "who", "whom", "when", "where",
            "why", "how", "all", "each", "every", "any", "few", "more", "most",
            "other", "some", "such", "up", "out", "if", "about", "into", "then",
            "there", "here", "just", "also", "only", "very", "too", "over",
            "after", "before", "between", "through", "during", "without", "upon"
    );

    private final HazelcastInstance hz;
    private final IMap<String, Boolean> locks;

    public HazelcastIndex() {

        ClientConfig clientConfig = new ClientConfig();
        String clusterName = System.getenv().getOrDefault("HZ_CLUSTER_NAME", "search-cluster");
        clientConfig.setClusterName(clusterName);

        // Identidad fija para evitar ambigüedad en el clúster
        clientConfig.setInstanceName("indexing-client");

        clientConfig.getConnectionStrategyConfig()
                .getConnectionRetryConfig()
                .setClusterConnectTimeoutMillis(20000);

        clientConfig.getNetworkConfig()
                .setSmartRouting(false) // Deshabilitado para evitar conflictos de red
                .setRedoOperation(true)
                .addAddress(
                        "hazelcast-1:5701",
                        "hazelcast-2:5701",
                        "hazelcast-3:5701"
                );

        hz = HazelcastClient.newHazelcastClient(clientConfig);
        locks = hz.getMap("index_locks");

        System.out.println("[HazelcastIndex] Connected to Hazelcast cluster. Members: "
                + hz.getCluster().getMembers().size());
    }

    public boolean isConnected() {
        return hz != null && hz.getLifecycleService().isRunning();
    }

    public void indexBook(Map<String, Object> book) {
        String content = (String) book.get("content");
        if (content == null) return;

        int bookId = (Integer) book.get("book_id");

        String[] tokens = content.toLowerCase()
                .replaceAll("[^a-záéíóúüñ\\s]", "")
                .split("\\s+");

        int indexed = 0;
        for (String raw : tokens) {
            String term = raw.trim();
            if (term.length() <= 2) continue;
            if (STOP_WORDS.contains(term)) continue;

            String lockKey = "index:" + term;

            locks.lock(lockKey);
            try {
                MultiMap<String, Integer> shardMap = getShard(term);
                shardMap.put(term, bookId);
                indexed++;
            } finally {
                locks.unlock(lockKey);
            }
        }

        System.out.println("[HazelcastIndex] Indexed book " + bookId +
                " — total tokens: " + tokens.length + ", indexed (no stop words): " + indexed);
    }

    public List<Integer> searchTerm(String term) {
        if (term == null || term.isBlank()) return List.of();

        String normalized = term.toLowerCase().trim();

        MultiMap<String, Integer> shardMap = getShard(normalized);
        return new ArrayList<>(shardMap.get(normalized));
    }

    public void deleteBook(int bookId) {
        System.out.println("[HazelcastIndex] Skipping deleteBook for now to avoid client-side error.");
    }

    public void clearAll() {
        String lockKey = "global-reindex";

        locks.lock(lockKey);
        try {
            for (int shard = 0; shard < 3; shard++) {
                hz.getMultiMap("inverted_index_" + shard).clear();
            }
            System.out.println("[HazelcastIndex] Global index cleared");
        } finally {
            locks.unlock(lockKey);
        }
    }

    private MultiMap<String, Integer> getShard(String term) {
        int shard = Math.abs(term.hashCode()) % 3;
        return hz.getMultiMap("inverted_index_" + shard);
    }

    public void close() {
        if (hz != null) {
            System.out.println("[HazelcastIndex] Shutting down Hazelcast client...");
            hz.shutdown();
        }
    }
}