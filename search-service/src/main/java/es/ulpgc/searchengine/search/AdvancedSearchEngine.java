package es.ulpgc.searchengine.search;

import es.ulpgc.searchengine.search.repository.DatamartMongo;
import es.ulpgc.searchengine.search.hazelcast.HazelcastIndexClient;

import java.util.*;

public class AdvancedSearchEngine {

    private final DatamartMongo repo;
    private final HazelcastIndexClient hz;

    public AdvancedSearchEngine(DatamartMongo repo, HazelcastIndexClient hz) {
        this.repo = repo;
        this.hz = hz;
    }

    // ------------------------------------------------------------
    // BASIC SEARCH
    // ------------------------------------------------------------
    public List<Map<String,Object>> search(String term) {
        if (term == null || term.isBlank()) return List.of();

        List<Integer> ids = hz.searchTerm(term);
        if (ids.isEmpty()) return List.of();

        return repo.getBooksByIds(ids);
    }

    // ------------------------------------------------------------
    // PHRASE SEARCH (Hazelcast → candidatos, Mongo → verificación)
    // ------------------------------------------------------------
    public List<Map<String,Object>> searchPhrase(String phrase) {
        if (phrase == null || phrase.isBlank()) return List.of();

        String[] tokens = phrase.toLowerCase().split("\\s+");
        if (tokens.length == 0) return List.of();

        // 1) candidatos por primera palabra
        List<Integer> candidates = hz.searchTerm(tokens[0]);
        if (candidates.isEmpty()) return List.of();

        // 2) verificación exacta en Mongo
        List<Map<String,Object>> books = repo.getBooksByIds(candidates);
        List<Map<String,Object>> result = new ArrayList<>();

        String needle = phrase.toLowerCase();

        for (Map<String,Object> book : books) {
            String content = (String) book.get("content");
            if (content != null && content.toLowerCase().contains(needle)) {
                result.add(book);
            }
        }

        return result;
    }

    // ------------------------------------------------------------
    // BOOLEAN SEARCH (AND / OR)
    // ------------------------------------------------------------
    public List<Map<String,Object>> booleanSearch(String expr) {
        if (expr == null || expr.isBlank()) return List.of();

        String[] parts = expr.split("\\s+");
        if (parts.length < 3) {
            return search(expr);
        }

        String term1 = parts[0].toLowerCase();
        String operator = parts[1].toUpperCase();
        String term2 = parts[2].toLowerCase();

        Set<Integer> set1 = new LinkedHashSet<>(hz.searchTerm(term1));
        Set<Integer> set2 = new LinkedHashSet<>(hz.searchTerm(term2));

        if ("AND".equals(operator)) {
            set1.retainAll(set2);
        } else if ("OR".equals(operator)) {
            set1.addAll(set2);
        }

        if (set1.isEmpty()) return List.of();

        return repo.getBooksByIds(new ArrayList<>(set1));
    }

    // ------------------------------------------------------------
    // RANGE SEARCH (Hazelcast → candidatos, Mongo → filtro por año)
    // ------------------------------------------------------------
    public List<Map<String,Object>> searchByYearRange(int startYear, int endYear, String term) {
        if (term == null || term.isBlank()) return List.of();

        List<Integer> candidates = hz.searchTerm(term);
        if (candidates.isEmpty()) return List.of();

        List<Map<String,Object>> books = repo.getBooksByIds(candidates);
        List<Map<String,Object>> result = new ArrayList<>();

        for (Map<String,Object> book : books) {
            int year = (Integer) book.getOrDefault("year", -1);
            if (year >= startYear && year <= endYear) {
                result.add(book);
            }
        }

        return result;
    }
}
