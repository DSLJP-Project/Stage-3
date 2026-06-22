package es.ulpgc.searchengine.ingestion;

import com.google.gson.Gson;
import es.ulpgc.searchengine.ingestion.messaging.IngestionPublisher;
import es.ulpgc.searchengine.ingestion.utils.DatalakeManager;
import es.ulpgc.searchengine.ingestion.utils.ReplicaClient;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public class IngestionEngine {

    private final Gson gson = new Gson();

    private final Path datalakePath;
    private final int replicationFactor;
    private final String peersRaw;
    private final List<String> peers;

    private final DatalakeManager datalakeManager;
    private final ReplicaClient replicaClient;

    private final IngestionPublisher publisher;

    public IngestionEngine() {
        this.datalakePath = Paths.get(System.getenv().getOrDefault("DATALAKE_PATH", "/app/datalake/replica-0"));

        String rfRaw = System.getenv().getOrDefault("REPLICATION_FACTOR", "1");
        int rf;
        try {
            rf = Integer.parseInt(rfRaw);
        } catch (Exception e) {
            rf = 1;
        }
        this.replicationFactor = Math.max(1, rf);

        this.peersRaw = System.getenv().getOrDefault("INGESTION_PEERS", "");
        this.peers = parsePeers(peersRaw);

        this.datalakeManager = new DatalakeManager(datalakePath);
        this.replicaClient = new ReplicaClient(gson);

        String brokerUrl = System.getenv().getOrDefault("BROKER_URL", "tcp://activemq:61616");
        String queue = System.getenv().getOrDefault("INGESTION_QUEUE", "document.ingested");
        this.publisher = new IngestionPublisher(brokerUrl, queue);
    }

    public String getDatalakePath() {
        return datalakePath.toString();
    }

    public int getReplicationFactor() {
        return replicationFactor;
    }

    public String getPeersRaw() {
        return peersRaw;
    }

    public Map<String, Object> ingest(String bookId, String content) {
        // 1) write local
        boolean localOk = datalakeManager.write(bookId, content);

        int confirmations = localOk ? 1 : 0;

        // 2) replicate to peers until reach RF
        List<String> attempted = new ArrayList<>();
        List<String> okPeers = new ArrayList<>();
        List<String> failedPeers = new ArrayList<>();

        if (localOk && replicationFactor > 1) {
            for (String peer : peers) {
                if (confirmations >= replicationFactor) break;
                attempted.add(peer);

                boolean ok = replicaClient.sendReplica(peer, bookId, content);
                if (ok) {
                    confirmations++;
                    okPeers.add(peer);
                } else {
                    failedPeers.add(peer);
                }
            }
        }

        // 3) publish event with JSON (book_id + content)
        boolean published = false;
        if (localOk) {
            Map<String, Object> event = Map.of(
                    "book_id", bookId,
                    "content", content
            );

            publisher.publish(gson.toJson(event));
            published = true;
        }

        return new LinkedHashMap<>(Map.of(
                "ok", localOk,
                "book_id", bookId,
                "replicas_confirmed", confirmations,
                "replication_factor", replicationFactor,
                "attempted_peers", attempted,
                "replicated_peers", okPeers,
                "failed_peers", failedPeers,
                "published", published,
                "datalake_path", datalakePath.toString()
        ));
    }

    public boolean storeReplica(String bookId, String content) {
        return datalakeManager.write(bookId, content);
    }

    public Map<String, Object> status(String bookId) {
        boolean exists = datalakeManager.exists(bookId);
        return new LinkedHashMap<>(Map.of(
                "book_id", bookId,
                "exists_local", exists,
                "datalake_path", datalakePath.toString()
        ));
    }

    private static List<String> parsePeers(String raw) {
        if (raw == null) return List.of();
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return List.of();
        String[] parts = trimmed.split(",");
        List<String> out = new ArrayList<>();
        for (String p : parts) {
            String s = p.trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }
}
