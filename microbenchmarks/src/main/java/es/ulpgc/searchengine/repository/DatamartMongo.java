package es.ulpgc.searchengine.repository;

import com.mongodb.client.*;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import org.bson.Document;

import java.util.*;
import java.util.regex.Pattern;

public class DatamartMongo {
    private final MongoClient client;
    private final MongoDatabase database;
    private final MongoCollection<Document> books;

    public DatamartMongo(String uri, String dbName, String collection) {
        this.client = MongoClients.create(uri);
        this.database = client.getDatabase(dbName);
        this.books = database.getCollection(collection);
        initSchema();
    }

    private void initSchema() {
        books.createIndex(
                Indexes.ascending("book_id"),
                new IndexOptions().unique(true)
        );
        System.out.println("[Microbench DatamartMongo] Indexes ensured");
    }

    public void insertBook(int id, String title, String author, String language, int year, String content, String hash) {
        Document doc = new Document("book_id", id)
                .append("title", title)
                .append("author", author)
                .append("language", language)
                .append("year", year)
                .append("content", content)
                .append("content_hash", hash);

        books.replaceOne(Filters.eq("book_id", id), doc,
                new com.mongodb.client.model.ReplaceOptions().upsert(true));
    }

    public void deleteBook(int id) {
        books.deleteOne(Filters.eq("book_id", id));
    }

    public Map<String,Object> getBook(int id) {
        Document doc = books.find(Filters.eq("book_id", id)).first();
        return doc != null ? toMap(doc) : null;
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

    public int countBooks() {
        return (int) books.countDocuments();
    }

    public boolean testConnection() {
        try {
            books.countDocuments(Filters.eq("_id", "__healthcheck__"));
            return true;
        } catch (Exception e) {
            System.err.println("[Microbench DatamartMongo] Error testConnection: " + e.getMessage());
            return false;
        }
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
