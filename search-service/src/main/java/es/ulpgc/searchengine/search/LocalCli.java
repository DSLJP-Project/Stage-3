package es.ulpgc.searchengine.search;

import es.ulpgc.searchengine.search.repository.DatamartMongo;
import es.ulpgc.searchengine.search.hazelcast.HazelcastIndexClient;

import java.util.*;

public class LocalCli {

    private enum Mode { BASIC, PHRASE, BOOLEAN, RANGE }

    private static final class Query {
        final Mode mode;
        final String term;
        final String phrase;
        final String booleanExpr;
        final Integer startYear;
        final Integer endYear;
        final String author;
        final String language;
        final Integer year;

        Query(Mode mode, String term, String phrase, String booleanExpr,
              Integer startYear, Integer endYear,
              String author, String language, Integer year) {

            this.mode = mode;
            this.term = term;
            this.phrase = phrase;
            this.booleanExpr = booleanExpr;
            this.startYear = startYear;
            this.endYear = endYear;
            this.author = author;
            this.language = language;
            this.year = year;
        }
    }

    public static void main(String[] args) {

        String mongoUri = System.getenv().getOrDefault("MONGO_URI", "mongodb://mongo:27017");
        String dbName = System.getenv().getOrDefault("MONGO_DB", "searchengine");
        String collection = System.getenv().getOrDefault("MONGO_COLLECTION", "books");

        DatamartMongo repo = new DatamartMongo(mongoUri, dbName, collection);
        HazelcastIndexClient hz = new HazelcastIndexClient();
        AdvancedSearchEngine engine = new AdvancedSearchEngine(repo, hz);

        System.out.println("Local Search CLI (no HTTP)");
        System.out.println("Mongo: " + mongoUri + "/" + dbName + "." + collection);
        System.out.println("Hazelcast: " + (hz.isConnected() ? "connected" : "not connected"));
        System.out.println("Type a query (or 'exit' to quit). Type 'help' for examples.\n");

        try (Scanner in = new Scanner(System.in)) {
            while (true) {
                System.out.print("> ");
                String line = in.nextLine();
                if (line == null) break;
                line = line.trim();
                if (line.isEmpty()) continue;

                if (line.equalsIgnoreCase("exit")) break;
                if (line.equalsIgnoreCase("help")) {
                    printHelp();
                    continue;
                }

                Query q = parseQuery(line);
                List<Map<String,Object>> results = execute(q, engine, repo);
                printResults(q.mode.name().toLowerCase(), displayQuery(q), results);
            }
        }

        hz.close();
        System.out.println("Leaving the search interface");
    }

    private static Query parseQuery(String input) {
        String author = null, language = null;
        Integer year = null;
        String remaining = input;

        for (String token : input.split("\\s+")) {
            String lower = token.toLowerCase();
            if (lower.startsWith("author:")) {
                author = token.substring("author:".length());
                remaining = remaining.replace(token, "").trim();
            } else if (lower.startsWith("language:")) {
                language = token.substring("language:".length());
                remaining = remaining.replace(token, "").trim();
            } else if (lower.startsWith("year:")) {
                try {
                    year = Integer.parseInt(token.substring("year:".length()));
                    remaining = remaining.replace(token, "").trim();
                } catch (NumberFormatException ignored) {}
            }
        }

        String phrase = extractQuotedAfterPrefix(remaining, "phrase:");
        if (phrase != null)
            return new Query(Mode.PHRASE, null, phrase, null, null, null, author, language, year);

        Map<String,String> range = extractRange(remaining);
        if (!range.isEmpty()) {
            Integer start = parseIntOrNull(range.get("start"));
            Integer end = parseIntOrNull(range.get("end"));
            String term = trimToNull(range.get("q"));
            if (start != null && end != null)
                return new Query(Mode.RANGE, term, null, null, start, end, author, language, year);
        }

        if (remaining.toLowerCase().startsWith("bool:")) {
            String expr = remaining.substring("bool:".length()).trim();
            return new Query(Mode.BOOLEAN, null, null, expr, null, null, author, language, year);
        }

        return new Query(Mode.BASIC, remaining.trim(), null, null, null, null, author, language, year);
    }

    private static List<Map<String, Object>> execute(Query q, AdvancedSearchEngine engine, DatamartMongo repo) {
        switch (q.mode) {
            case PHRASE:
                return engine.searchPhrase(q.phrase);
            case BOOLEAN:
                return engine.booleanSearch(q.booleanExpr);
            case RANGE:
                return engine.searchByYearRange(q.startYear, q.endYear, q.term);
            default:
                return engine.search(q.term);
        }
    }

    private static void printHelp() {
        System.out.println();
        System.out.println("Examples:");
        System.out.println("- adventure");
        System.out.println("- love author:Austen");
        System.out.println("- love language:en year:1813");
        System.out.println("- phrase:\"once upon a time\"");
        System.out.println("- range:start=1800,end=1850");
        System.out.println("- range:start=1800,end=1850 q=adventure");
        System.out.println("- bool: love AND war");
        System.out.println();
    }

    private static void printResults(String mode, String query, List<Map<String,Object>> results) {
        System.out.println("\n=== Results (" + mode + "): " + query + " ===");
        if (results == null || results.isEmpty()) {
            System.out.println("(no results)\n");
            return;
        }
        for (Map<String,Object> row : results) {
            System.out.println(String.format(
                    "- [%s] %s — %s (%s, %s)",
                    row.getOrDefault("book_id","?"),
                    row.getOrDefault("title","<no title>"),
                    row.getOrDefault("author","<no author>"),
                    row.getOrDefault("language","?"),
                    row.getOrDefault("year","?")
            ));
        }
        System.out.println("Total: " + results.size() + "\n");
    }

    private static String displayQuery(Query q) {
        switch (q.mode) {
            case PHRASE: return q.phrase;
            case BOOLEAN: return q.booleanExpr;
            case RANGE:
                String base = q.startYear + "-" + q.endYear;
                return q.term == null ? base : base + " q=" + q.term;
            default: return q.term;
        }
    }

    private static String extractQuotedAfterPrefix(String input, String prefix) {
        int i = input.toLowerCase().indexOf(prefix);
        if (i < 0) return null;
        String after = input.substring(i + prefix.length()).trim();
        if (after.startsWith("\"")) {
            int j = after.indexOf("\"", 1);
            if (j > 1) return after.substring(1, j);
        }
        return after.isEmpty() ? null : after;
    }

    private static Map<String,String> extractRange(String input) {
        Map<String,String> m = new HashMap<>();
        String low = input.toLowerCase();
        int i = low.indexOf("range:");
        if (i < 0) return m;
        String tail = input.substring(i + "range:".length()).trim();
        for (String part : tail.split("[,\\s]+")) {
            if (part.contains("=")) {
                String[] kv = part.split("=", 2);
                m.put(kv[0].trim().toLowerCase(), kv[1].trim());
            }
        }
        return m;
    }

    private static Integer parseIntOrNull(String s) {
        try { return (s == null || s.trim().isEmpty()) ? null : Integer.parseInt(s.trim()); }
        catch (Exception e) { return null; }
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static boolean equalsIgnoreCaseAny(String s, String... options) {
        for (String o : options) if (s.equalsIgnoreCase(o)) return true;
        return false;
    }
}
