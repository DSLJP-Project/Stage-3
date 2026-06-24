package es.ulpgc.searchengine;

import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;
import java.util.regex.*;
import java.util.*;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class TextBenchmarks {

    private String sampleText;

    @Setup(Level.Iteration)
    public void setup() {
        sampleText =
                "Title: Pride and Prejudice\n" +
                        "Author: Jane Austen\n" +
                        "Language: en\n" +
                        "Release Date: January 28, 2013 [eBook #1342]\n\n" +
                        "It is a truth universally acknowledged, that a single man in possession " +
                        "of a good fortune, must be in want of a wife. However little known the " +
                        "feelings or views of such a man may be on his first entering a neighbourhood, " +
                        "this truth is so well fixed in the minds of the surrounding families, " +
                        "that he is considered as the rightful property of some one or other of " +
                        "their daughters. \"My dear Mr. Bennet,\" said his lady to him one day, " +
                        "\"have you heard that Netherfield Park is let at last?\" Mr. Bennet replied " +
                        "that he had not. \"But it is,\" returned she; \"for Mrs. Long has just been " +
                        "here, and she told me all about it.\"";
    }

    // ─── Benchmark 1: construcción del índice invertido ───────────────────────
    @Benchmark
    public Map<String, List<Integer>> buildInvertedIndex() {
        Map<String, List<Integer>> index = new HashMap<>();
        String[] tokens = sampleText.toLowerCase()
                .replaceAll("[^a-záéíóúüñ\\s]", " ")
                .split("\\s+");

        for (int i = 0; i < tokens.length; i++) {
            String word = tokens[i].trim();
            if (word.length() > 2)   // coincide con lógica del Indexer real
                index.computeIfAbsent(word, k -> new ArrayList<>()).add(i);
        }
        return index;
    }

    // ─── Benchmark 2: extracción de título ───────────────────────────────────
    @Benchmark
    public String extractTitle() {
        Matcher m = Pattern.compile("^Title:\\s*(.*)", Pattern.MULTILINE).matcher(sampleText);
        return m.find() ? m.group(1).trim() : "Unknown";
    }

    // ─── Benchmark 3: extracción de autor ────────────────────────────────────
    @Benchmark
    public String extractAuthor() {
        Matcher m = Pattern.compile("^Author:\\s*(.*)", Pattern.MULTILINE).matcher(sampleText);
        return m.find() ? m.group(1).trim() : "Unknown";
    }

    // ─── Benchmark 4: extracción de año ──────────────────────────────────────
    @Benchmark
    public int extractYear() {
        Matcher m = Pattern.compile("\\b(1[0-9]{3}|20[0-2][0-9])\\b").matcher(sampleText);
        while (m.find()) {
            int year = Integer.parseInt(m.group(1));
            if (year >= 1000 && year <= 2025) return year;
        }
        return 0;
    }

    // ─── Benchmark 5: búsqueda de múltiples términos ─────────────────────────
    @Benchmark
    public Map<String, Object> searchMultipleTermsBenchmark() {
        Map<String, Object> results = new HashMap<>();
        for (String term : List.of("love", "war", "peace", "man", "truth")) {
            results.put(term, buildInvertedIndexForTerm(term));
        }
        return results;
    }

    // ─── Benchmark 6: búsqueda de frase exacta ────────────────────────────────
    @Benchmark
    public boolean phraseSearchBenchmark() {
        String phrase = "single man in possession";
        return sampleText.toLowerCase().contains(phrase.toLowerCase());
    }

    // ─── Benchmark 7: tokenización y normalización ────────────────────────────
    @Benchmark
    public String tokenizeAndNormalize() {
        return sampleText.toLowerCase()
                .replaceAll("[^a-záéíóúüñ\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    // ─── Benchmark 8: stop-words removal ─────────────────────────────────────
    @Benchmark
    public List<String> removeStopWords() {
        Set<String> stopWords = Set.of(
                "the", "a", "an", "and", "or", "but", "in", "on", "at",
                "to", "for", "of", "with", "is", "it", "its", "that", "this"
        );
        String[] tokens = sampleText.toLowerCase()
                .replaceAll("[^a-z\\s]", " ")
                .split("\\s+");
        List<String> filtered = new ArrayList<>();
        for (String t : tokens) {
            if (t.length() > 2 && !stopWords.contains(t))
                filtered.add(t);
        }
        return filtered;
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private Map<String, List<Integer>> buildInvertedIndexForTerm(String term) {
        Map<String, List<Integer>> index = new HashMap<>();
        String[] tokens = sampleText.toLowerCase()
                .replaceAll("[^a-záéíóúüñ\\s]", "")
                .split("\\s+");
        for (int i = 0; i < tokens.length; i++) {
            if (tokens[i].equals(term))
                index.computeIfAbsent(term, k -> new ArrayList<>()).add(i);
        }
        return index;
    }
}