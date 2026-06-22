package es.ulpgc.searchengine.ingestion.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

public class DatalakeStorage {

    private final Path base;

    public DatalakeStorage(String basePath) {
        this.base = Paths.get(basePath);
        try { Files.createDirectories(base); } catch (IOException ignored) {}
    }

    public record WriteResult(int bookId, String contentHash, Path dir) {}

    public WriteResult writeBook(int bookId, String header, String body) throws IOException {
        Path dir = base.resolve(String.valueOf(bookId));
        Files.createDirectories(dir);
        Path headerFile = dir.resolve("header.txt");
        Path bodyFile = dir.resolve("body.txt");

        // Idempotencia: comparar hash antes de sobrescribir
        String newHash = hash(body);
        String existingHash = readHashIfPresent(dir);
        if (existingHash != null && existingHash.equals(newHash)) {
            // No reescribir si es el mismo contenido
            return new WriteResult(bookId, existingHash, dir);
        }

        Files.writeString(headerFile, header, StandardCharsets.UTF_8);
        Files.writeString(bodyFile, body, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve(".hash"), newHash, StandardCharsets.UTF_8);

        return new WriteResult(bookId, newHash, dir);
    }

    public Map<String,Object> getStatus(int bookId) throws IOException {
        Path dir = base.resolve(String.valueOf(bookId));
        boolean exists = Files.exists(dir);
        String hash = exists ? readHashIfPresent(dir) : null;
        return Map.of(
                "book_id", bookId,
                "status", exists ? "available" : "missing",
                "content_hash", hash != null ? hash : ""
        );
    }

    public boolean testWritable() {
        try {
            Path test = base.resolve(".ready");
            Files.writeString(test, "ok", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.deleteIfExists(test);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private String readHashIfPresent(Path dir) {
        Path hashFile = dir.resolve(".hash");
        try {
            if (Files.exists(hashFile)) {
                return Files.readString(hashFile, StandardCharsets.UTF_8).trim();
            }
        } catch (IOException ignored) {}
        return null;
    }

    private String hash(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(dig);
        } catch (Exception e) {
            throw new RuntimeException("Hashing failed", e);
        }
    }
}
