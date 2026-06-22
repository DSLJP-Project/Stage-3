package es.ulpgc.searchengine.indexing;

import es.ulpgc.searchengine.indexing.messaging.IndexingEventConsumer;
import es.ulpgc.searchengine.indexing.messaging.ReindexRequestConsumer;
import es.ulpgc.searchengine.indexing.repository.DatamartMongo;
import es.ulpgc.searchengine.indexing.hazelcast.HazelcastIndex;
import io.javalin.Javalin;

public class IndexingApp {
    public static void main(String[] args) {
        String mongoUri = System.getenv().getOrDefault("MONGO_URI", "mongodb://mongo:27017");
        String mongoDb = System.getenv().getOrDefault("MONGO_DB", "searchengine");

        System.out.println("[IndexingApp] Iniciando servicio...");

        // 1. Inicializar Repositorios
        DatamartMongo repo = new DatamartMongo(mongoUri, mongoDb, "books");
        HazelcastIndex hz = new HazelcastIndex();

        // 2. Iniciar el consumidor (EL CONSUMIDOR SE CONECTA A ACTIVEMQ AQUÍ)
        IndexingEventConsumer consumer = new IndexingEventConsumer(repo, hz);
        ReindexRequestConsumer reindexConsumer = new ReindexRequestConsumer(repo, hz);
        if (!consumer.isReady() || !reindexConsumer.isReady()) {
            throw new IllegalStateException("ActiveMQ is not ready; Swarm will retry this indexer");
        }

        // 3. Iniciar el servidor web (Javalin)
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "7002"));
        Javalin app = Javalin.create().start("0.0.0.0", port);

        // 4. Registrar endpoints
        new IndexingController(repo, hz).register(app);

        System.out.println("[IndexingApp] Servicio iniciado y esperando mensajes...");

        // 5. ¡IMPORTANTE! Mantener vivo el hilo principal
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[IndexingApp] Cerrando servicio...");
            consumer.close();
            reindexConsumer.close();
            hz.close();
            repo.close();
        }));
    }
}
