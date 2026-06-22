package es.ulpgc.searchengine.ingestion.utils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

public class DatalakeManager {

    private final Path basePath;

    public DatalakeManager(Path basePath) {
        this.basePath = basePath;
        ensureBaseDir();
    }

    private void ensureBaseDir() {
        try {
            Files.createDirectories(basePath);
        } catch (IOException e) {
            throw new RuntimeException("Cannot create datalake dir: " + basePath, e);
        }
    }

    public boolean write(String bookId, String content) {
        try {
            Path file = resolveFile(bookId);
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return true;
        } catch (Exception e) {
            System.err.println("[DatalakeManager] write failed: " + e.getMessage());
            return false;
        }
    }

    public boolean exists(String bookId) {
        try {
            return Files.exists(resolveFile(bookId));
        } catch (Exception e) {
            return false;
        }
    }

    private Path resolveFile(String bookId) {
        // guardamos como: /datalake/{bookId}.txt (simple y visible)
        return basePath.resolve(bookId + ".txt");
    }
}
