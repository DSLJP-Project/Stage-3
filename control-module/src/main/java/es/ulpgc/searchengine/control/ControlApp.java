package es.ulpgc.searchengine.control;

import io.javalin.Javalin;

public class ControlApp {
    public static void main(String[] args) {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "7006"));

        Controller controller = new Controller();

        Javalin app = Javalin.create(cfg -> cfg.http.defaultContentType = "application/json").start(port);
        controller.register(app);

        app.get("/health", ctx -> ctx.status(200).result("OK"));

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { app.stop(); } catch (Exception ignored) {}
        }));

        System.out.println("[ControlApp] Control Module running on port " + port);
    }
}
