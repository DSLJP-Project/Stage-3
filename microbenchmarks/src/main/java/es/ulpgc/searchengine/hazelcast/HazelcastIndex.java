package es.ulpgc.searchengine.hazelcast;

import com.hazelcast.client.HazelcastClient;
import com.hazelcast.client.config.ClientConfig;
import com.hazelcast.config.EvictionConfig;
import com.hazelcast.config.EvictionPolicy;
import com.hazelcast.config.NearCacheConfig;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.multimap.MultiMap;

import java.util.*;

public class HazelcastIndex {
    private final HazelcastInstance hz;
    private final MultiMap<String, Integer> index;

    public HazelcastIndex() {
        ClientConfig clientConfig = new ClientConfig();
        clientConfig.setClusterName("search-cluster");

        clientConfig.getNetworkConfig()
                .addAddress("hazelcast-1:5701", "hazelcast-2:5701", "hazelcast-3:5701");

        clientConfig.getNearCacheConfigMap().put("inverted_index",
                new NearCacheConfig()
                        .setInvalidateOnChange(true)
                        .setTimeToLiveSeconds(0)
                        .setMaxIdleSeconds(0)
                        .setEvictionConfig(new EvictionConfig()
                                .setEvictionPolicy(EvictionPolicy.LRU)
                                .setSize(10000)
                        )
        );



        hz = HazelcastClient.newHazelcastClient(clientConfig);
        index = hz.getMultiMap("inverted_index");
    }

    public boolean isConnected() {
        return hz != null && hz.getLifecycleService().isRunning();
    }

    public void indexBook(Map<String,Object> book) {
        String content = (String) book.get("content");
        if (content == null) return;
        int bookId = (Integer) book.get("book_id");

        String[] tokens = content.toLowerCase()
                .replaceAll("[^a-záéíóúüñ\\s]", "")
                .split("\\s+");

        for (String raw : tokens) {
            String term = raw.trim();
            if (term.isEmpty()) continue;
            index.put(term, bookId);
        }
    }

    public List<Integer> searchTerm(String term) {
        if (term == null || term.isBlank()) return List.of();
        Collection<Integer> postings = index.get(term.toLowerCase());
        if (postings == null || postings.isEmpty()) return List.of();
        return new ArrayList<>(new LinkedHashSet<>(postings));
    }

    public void deleteBook(int id) {
        for (String term : index.keySet()) {
            index.remove(term, id);
        }
    }

    public void close() {
        if (hz != null) hz.shutdown();
    }
}
