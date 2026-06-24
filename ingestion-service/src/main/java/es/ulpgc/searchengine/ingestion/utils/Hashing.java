package es.ulpgc.searchengine.ingestion.utils;

import java.security.MessageDigest;
import java.util.HexFormat;

public class Hashing {
    public static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new RuntimeException("Hashing error", e);
        }
    }
}
