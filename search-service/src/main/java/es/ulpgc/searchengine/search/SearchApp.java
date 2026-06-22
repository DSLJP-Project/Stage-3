package es.ulpgc.searchengine.search;

import es.ulpgc.searchengine.search.hazelcast.HazelcastIndex;
import es.ulpgc.searchengine.search.repository.DatamartMongo;
import io.javalin.Javalin;

public class SearchApp {

    public static void main(String[] args) {

        String mongoUri  = System.getenv().getOrDefault("MONGO_URI",        "mongodb://mongo:27017");
        String mongoDb   = System.getenv().getOrDefault("MONGO_DB",         "searchengine");
        String mongoColl = System.getenv().getOrDefault("MONGO_COLLECTION", "books");
        int    port      = Integer.parseInt(System.getenv().getOrDefault("PORT", "7003"));

        DatamartMongo  datamart = new DatamartMongo(mongoUri, mongoDb, mongoColl);
        HazelcastIndex hzIndex  = new HazelcastIndex();

        Javalin app = Javalin.create(cfg ->
                cfg.http.defaultContentType = "application/json");

        SearchController controller = new SearchController(datamart, hzIndex);
        controller.register(app);

        app.start("0.0.0.0", port);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { hzIndex.close();      } catch (Exception ignored) {}
            try { datamart.close();     } catch (Exception ignored) {}
            try { app.stop();           } catch (Exception ignored) {}
        }));

        System.out.printf("[SearchApp] Search Service running on port %d | MongoDB %s/%s/%s%n",
                port, mongoUri, mongoDb, mongoColl);
    }
}
