package es.ulpgc.searchengine;

import org.openjdk.jmh.annotations.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class FileSystemBenchmarks {

    private Path basePath;

    @Setup(Level.Iteration)
    public void setup() {
        try {
            // Usar directorio temporal para no contaminar el datalake real
            basePath = Files.createTempDirectory("datalake-bench-");

            // Crear estructura de ejemplo con archivos reales (header + content)
            Path bookDir = basePath.resolve("20240115/14/30/12345");
            Files.createDirectories(bookDir);

            Files.writeString(bookDir.resolve("header.txt"),
                    "Title: Robinson Crusoe\n" +
                            "Author: Daniel Defoe\n" +
                            "Language: en\n" +
                            "Release Date: January 1, 2000 [eBook #5]\n",
                    StandardCharsets.UTF_8);

            Files.writeString(bookDir.resolve("content.txt"),
                    "I was born in the year 1632 in the city of York, of a good family, " +
                            "though not of that country, my father being a foreigner of Bremen, " +
                            "who settled first at Hull. He got a good estate by merchandise and " +
                            "leaving off his trade, lived afterwards at York, from whence he had " +
                            "married my mother, whose relations were named Robinson.",
                    StandardCharsets.UTF_8);

        } catch (IOException e) {
            System.err.println("[FileSystemBenchmarks] Setup error: " + e.getMessage());
        }
    }

    @TearDown(Level.Iteration)
    public void tearDown() {
        if (basePath == null) return;
        try (Stream<Path> paths = Files.walk(basePath)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
        } catch (IOException e) {
            System.err.println("[FileSystemBenchmarks] TearDown error: " + e.getMessage());
        }
    }

    // ─── Benchmark 1: búsqueda por ID con Files.walk ──────────────────────────
    @Benchmark
    public boolean findBookInNewStructure() {
        try (Stream<Path> paths = Files.walk(basePath)) {
            return paths.filter(Files::isDirectory)
                    .anyMatch(p -> p.getFileName().toString().equals("12345"));
        } catch (IOException e) {
            return false;
        }
    }

    // ─── Benchmark 2: búsqueda por ruta directa (sin walk) ───────────────────
    @Benchmark
    public boolean checkBookExistsByTimestamp() {
        Path specificPath = basePath.resolve("20240115/14/30/12345");
        return Files.exists(specificPath) && Files.isDirectory(specificPath);
    }

    // ─── Benchmark 3: lectura del contenido del libro ─────────────────────────
    @Benchmark
    public long readBookContent() {
        try {
            Path contentFile = basePath.resolve("20240115/14/30/12345/content.txt");
            if (Files.exists(contentFile)) {
                String content = Files.readString(contentFile, StandardCharsets.UTF_8);
                return content.length();
            }
            return 0;
        } catch (IOException e) {
            return -1;
        }
    }

    // ─── Benchmark 4: lectura del header del libro ────────────────────────────
    @Benchmark
    public long readBookHeader() {
        try {
            Path headerFile = basePath.resolve("20240115/14/30/12345/header.txt");
            if (Files.exists(headerFile)) {
                String header = Files.readString(headerFile, StandardCharsets.UTF_8);
                return header.length();
            }
            return 0;
        } catch (IOException e) {
            return -1;
        }
    }

    // ─── Benchmark 5: creación de directorio con timestamp ────────────────────
    @Benchmark
    public Path createTimestampedDirectory() {
        try {
            Path dir = basePath.resolve(buildTimestampPath("book-" + System.nanoTime()));
            Files.createDirectories(dir);
            return dir;
        } catch (IOException e) {
            return null;
        }
    }

    // ─── Benchmark 6: escritura completa de un libro (header + content) ───────
    @Benchmark
    public Path createBookStructure() {
        try {
            Path docDir = basePath.resolve(
                    buildTimestampPath("book-" + System.nanoTime()));
            Files.createDirectories(docDir);

            Files.writeString(docDir.resolve("header.txt"),
                    "Title: Benchmark Book\nAuthor: Bench Author\nLanguage: en\nYear: 2024",
                    StandardCharsets.UTF_8);
            Files.writeString(docDir.resolve("content.txt"),
                    "This is benchmark content for filesystem write performance testing.",
                    StandardCharsets.UTF_8);
            return docDir;
        } catch (IOException e) {
            return null;
        }
    }

    // ─── Benchmark 7: conteo de libros en el datalake ────────────────────────
    @Benchmark
    public int countBooksInStructure() {
        try (Stream<Path> paths = Files.walk(basePath)) {
            return (int) paths
                    .filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().matches("\\d+|book-\\d+"))
                    .count();
        } catch (IOException e) {
            return 0;
        }
    }

    // ─── Benchmark 8: walk completo del datalake ──────────────────────────────
    @Benchmark
    public int walkTemporalStructure() {
        try (Stream<Path> paths = Files.walk(basePath)) {
            return (int) paths.filter(Files::isDirectory).count();
        } catch (IOException e) {
            return 0;
        }
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private String buildTimestampPath(String bookId) {
        LocalDateTime now = LocalDateTime.now();
        return String.format("%s/%02d/%02d/%s",
                now.toLocalDate().toString().replace("-", ""),
                now.getHour(),
                now.getMinute(),
                bookId);
    }
}