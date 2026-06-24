package es.ulpgc.searchengine.benchmarks.repository;

import com.mongodb.client.*;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import org.bson.Document;

import java.util.*;
import java.util.regex.Pattern;

public class DatamartMongo {
    private final MongoClient client;
    private final MongoCollection<Document> books;

    public DatamartMongo(String uri, String dbName, String collection) {
        this.client = MongoClients.create(uri);
        MongoDatabase database = client.getDatabase(dbName);
        this.books = database.getCollection(collection);
        initSchema();
    }

    private void initSchema() {
        books.createIndex(
                Indexes.ascending("book_id"),
                new IndexOptions().unique(true)
        );
        books.createIndex(Indexes.ascending("year"));
        System.out.println("[Benchmark DatamartMongo] Indexes ensured");
    }

    public List<Map<String,Object>> searchTerm(String term) {
        List<Map<String,Object>> res = new ArrayList<>();
        if (term == null || term.isBlank()) return res;
        String regex = ".*" + Pattern.quote(term) + ".*";
        try (MongoCursor<Document> cursor = books
                .find(Filters.regex("content", regex, "i")).iterator()) {
            while (cursor.hasNext()) res.add(toMap(cursor.next()));
        }
        return res;
    }

    public List<Map<String,Object>> searchPhrase(String phrase) {
        return searchTerm(phrase);
    }

    /** Búsqueda booleana básica con AND (igual que tu versión SQLite) */
    public List<Map<String,Object>> searchBoolean(String query) {
        List<Map<String,Object>> res = new ArrayList<>();
        if (query == null || query.isBlank()) return res;

        String[] terms = query.split("\\bAND\\b");
        List<String> cleaned = new ArrayList<>();
        for (String t : terms) {
            String s = t.trim();
            if (!s.isEmpty()) cleaned.add(s);
        }
        if (cleaned.isEmpty()) return res;

        // construimos filtros AND de regex sobre content
        List<org.bson.conversions.Bson> filters = new ArrayList<>();
        for (String t : cleaned) {
            String regex = ".*" + Pattern.quote(t) + ".*";
            filters.add(Filters.regex("content", regex, "i"));
        }

        try (MongoCursor<Document> cursor = books
                .find(Filters.and(filters)).iterator()) {
            while (cursor.hasNext()) res.add(toMap(cursor.next()));
        }
        return res;
    }

    public List<Map<String,Object>> queryByYearRange(int start, int end) {
        List<Map<String,Object>> res = new ArrayList<>();
        try (MongoCursor<Document> cursor = books.find(
                Filters.and(
                        Filters.gte("year", start),
                        Filters.lte("year", end)
                )
        ).iterator()) {
            while (cursor.hasNext()) res.add(toMap(cursor.next()));
        }
        return res;
    }

    public Map<String,Object> getBook(int id) {
        Document doc = books.find(Filters.eq("book_id", id)).first();
        return doc != null ? toMap(doc) : null;
    }

    public List<Map<String,Object>> getAllBooks() {
        List<Map<String,Object>> res = new ArrayList<>();
        try (MongoCursor<Document> cursor = books.find().iterator()) {
            while (cursor.hasNext()) res.add(toMap(cursor.next()));
        }
        return res;
    }

    public int countBooks() {
        return (int) books.countDocuments();
    }

    public boolean testConnection() {
        try {
            books.countDocuments(Filters.eq("_id", "__healthcheck__"));
            return true;
        } catch (Exception e) {
            System.err.println("[Benchmark DatamartMongo] Error testConnection: " + e.getMessage());
            return false;
        }
    }

    public List<Map<String, Object>> getBooksByIds(List<Integer> ids) {

        List<Map<String, Object>> res = new ArrayList<>();
        if (ids == null || ids.isEmpty()) return res;

        for (Integer id : ids) {
            Map<String, Object> book = getBook(id);
            if (book != null) {
                res.add(book);
            }
        }
        return res;
    }


    public void close() {
        try { client.close(); } catch (Exception ignored) {}
    }

    private Map<String,Object> toMap(Document doc) {
        Map<String,Object> row = new HashMap<>();
        row.put("book_id", doc.getInteger("book_id"));
        row.put("title", doc.getString("title"));
        row.put("author", doc.getString("author"));
        row.put("language", doc.getString("language"));
        row.put("year", doc.getInteger("year", 0));
        row.put("content", doc.getString("content"));
        row.put("content_hash", doc.getString("content_hash"));
        return row;
    }
}
