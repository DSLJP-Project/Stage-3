package es.ulpgc.searchengine.search.hazelcast;

import com.hazelcast.client.HazelcastClient;
import com.hazelcast.client.config.ClientConfig;
import com.hazelcast.config.EvictionConfig;
import com.hazelcast.config.EvictionPolicy;
import com.hazelcast.config.NearCacheConfig;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.multimap.MultiMap;

import java.util.*;

public class HazelcastIndexClient {

    private final HazelcastInstance hazelcast;

    public HazelcastIndexClient() {

        ClientConfig clientConfig = new ClientConfig();

        String clusterName = System.getenv().getOrDefault("HZ_CLUSTER_NAME", "search-cluster");
        clientConfig.setClusterName(clusterName);

        clientConfig.getNetworkConfig().addAddress(
                "hazelcast-1:5701",
                "hazelcast-2:5701",
                "hazelcast-3:5701"
        );

        NearCacheConfig nearCacheConfig =
                new NearCacheConfig("inverted_index_*")
                        .setInvalidateOnChange(true)
                        .setEvictionConfig(
                                new EvictionConfig()
                                        .setEvictionPolicy(EvictionPolicy.LRU)
                                        .setSize(10_000)
                        );

        clientConfig.addNearCacheConfig(nearCacheConfig);

        this.hazelcast = HazelcastClient.newHazelcastClient(clientConfig);
    }

    private String shardFor(String term) {
        int h = Math.abs(term.hashCode());
        return "inverted_index_" + (h % 3);
    }

    public List<Integer> searchTerm(String term) {
        if (term == null || term.isBlank()) return List.of();

        String normalized = term.toLowerCase();
        String shardName = shardFor(normalized);

        MultiMap<String, Integer> shard = hazelcast.getMultiMap(shardName);
        Collection<Integer> ids = shard.get(normalized);

        if (ids == null) return List.of();
        return new ArrayList<>(ids);
    }

    public boolean isConnected() {
        return hazelcast != null && hazelcast.getLifecycleService().isRunning();
    }

    public void close() {
        if (hazelcast != null) hazelcast.shutdown();
    }
}