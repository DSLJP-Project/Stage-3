package es.ulpgc.searchengine.indexing.repository;

import com.mongodb.client.*;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import org.bson.Document;

import java.util.*;

public class DatamartMongo {

    private final MongoClient client;
    private final MongoDatabase database;
    private final MongoCollection<Document> books;
    private final MongoCollection<Document> invertedIndex;

    public DatamartMongo(String uri, String dbName, String books) {
        this.client = MongoClients.create(uri);
        this.database = client.getDatabase(dbName);
        this.books = database.getCollection("books");
        this.invertedIndex = database.getCollection("inverted_index");
        initSchema();
    }

    private void initSchema() {
        // books: índice único por book_id
        books.createIndex(
                Indexes.ascending("book_id"),
                new IndexOptions().unique(true)
        );
        books.createIndex(Indexes.ascending("year"));

        // inverted_index: índice único por (term, book_id)
        invertedIndex.createIndex(
                Indexes.ascending("term", "book_id"),
                new IndexOptions().unique(true)
        );

        System.out.println("[Indexing DatamartMongo] Indexes ensured");
    }

    public Map<String,Object> getBook(int id) {
        Document doc = books.find(Filters.eq("book_id", id)).first();
        return doc != null ? toBookMap(doc) : null;
    }

    public void insertOrUpdateBook(int bookId, String title, String author,
                                   String language, int year, String content, String contentHash) {
        Document doc = new Document("book_id", bookId)
                .append("title", title)
                .append("author", author)
                .append("language", language)
                .append("year", year)
                .append("content", content)
                .append("content_hash", contentHash);

        books.replaceOne(Filters.eq("book_id", bookId), doc,
                new com.mongodb.client.model.ReplaceOptions().upsert(true));
    }

    public void insertOrUpdateBook(int bookId, String title, String author,
                                   String language, int year, String content) {
        insertOrUpdateBook(bookId, title, author, language, year, content, "");
    }

    public void insertIndex(String term, int bookId, List<Integer> positions) {
        Document doc = new Document("term", term)
                .append("book_id", bookId)
                .append("positions", positions);

        invertedIndex.replaceOne(
                Filters.and(
                        Filters.eq("term", term),
                        Filters.eq("book_id", bookId)
                ),
                doc,
                new com.mongodb.client.model.ReplaceOptions().upsert(true)
        );
    }

    public void insertIndex(int bookId, Map<String,List<Integer>> index) {
        for (Map.Entry<String,List<Integer>> e : index.entrySet()) {
            insertIndex(e.getKey(), bookId, e.getValue());
        }
    }

    public void deleteIndexForBook(int bookId) {
        invertedIndex.deleteMany(Filters.eq("book_id", bookId));
    }

    public Map<String,Object> getStats() {
        Map<String,Object> stats = new HashMap<>();
        stats.put("books", books.countDocuments());
        stats.put("terms", invertedIndex.countDocuments());
        return stats;
    }

    public boolean testConnection() {
        try {
            books.countDocuments(Filters.eq("_id", "__healthcheck__"));
            return true;
        } catch (Exception e) {
            System.err.println("[Indexing DatamartMongo] testConnection error: " + e.getMessage());
            return false;
        }
    }

    public void close() {
        try { client.close(); } catch (Exception ignored) {}
    }

    private Map<String,Object> toBookMap(Document doc) {
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
    public List<Map<String, Object>> getAllBooks() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Document doc : books.find()) {
            result.add(toBookMap(doc));
        }
        return result;
    }
}
