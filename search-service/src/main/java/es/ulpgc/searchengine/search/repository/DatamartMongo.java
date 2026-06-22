package es.ulpgc.searchengine.search.repository;

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

    public DatamartMongo(String uri, String dbName, String collectionName) {
        this.client = MongoClients.create(uri);
        this.database = client.getDatabase(dbName);
        this.books = database.getCollection(collectionName);
        initSchema();
    }

    /** Crea índices necesarios (equivalente al initSchema de SQLite) */
    private void initSchema() {
        // Índice único por book_id
        books.createIndex(
                Indexes.ascending("book_id"),
                new IndexOptions().unique(true)
        );
        // Índice por año para consultas por rango
        books.createIndex(Indexes.ascending("year"));
        // Opcional: índice de texto sobre contenido
        // books.createIndex(Indexes.text("content"));
        System.out.println("[Search DatamartMongo] Indexes ensured");
    }

    /** Inserta o actualiza un libro */
    public void insertBook(int id, String title, String author,
                           String language, int year, String content, String hash) {
        Document doc = new Document("book_id", id)
                .append("title", title)
                .append("author", author)
                .append("language", language)
                .append("year", year)
                .append("content", content)
                .append("content_hash", hash);

        books.replaceOne(Filters.eq("book_id", id), doc, new com.mongodb.client.model.ReplaceOptions().upsert(true));
    }

    /** Recupera un libro por ID */
    public Map<String, Object> getBook(int id) {
        Document doc = books.find(Filters.eq("book_id", id)).first();
        return doc != null ? toMap(doc) : null;
    }

    /** Recupera todos los libros */
    public List<Map<String, Object>> getAllBooks() {
        List<Map<String, Object>> res = new ArrayList<>();
        try (MongoCursor<Document> cursor = books.find().iterator()) {
            while (cursor.hasNext()) {
                res.add(toMap(cursor.next()));
            }
        }
        return res;
    }

    /** Alias para compatibilidad con AdvancedSearchEngine */
    public List<Map<String, Object>> allBooks() {
        return getAllBooks();
    }

    /** Recupera varios libros por lista de ID */
    public List<Map<String, Object>> getBooksByIds(List<Integer> ids) {
        List<Map<String, Object>> res = new ArrayList<>();
        if (ids == null || ids.isEmpty()) return res;
        try (MongoCursor<Document> cursor = books.find(Filters.in("book_id", ids)).iterator()) {
            while (cursor.hasNext()) {
                res.add(toMap(cursor.next()));
            }
        }
        return res;
    }

    /** Consulta por rango de años */
    public List<Map<String, Object>> queryByYearRange(int startYear, int endYear) {
        List<Map<String, Object>> res = new ArrayList<>();
        try (MongoCursor<Document> cursor = books
                .find(Filters.and(
                        Filters.gte("year", startYear),
                        Filters.lte("year", endYear)
                )).iterator()) {
            while (cursor.hasNext()) {
                res.add(toMap(cursor.next()));
            }
        }
        return res;
    }

    /** Búsqueda simple por término en content (LIKE equivalente con regex) */
    public List<Map<String, Object>> searchTerm(String term) {
        List<Map<String, Object>> res = new ArrayList<>();
        if (term == null || term.isBlank()) return res;

        // Regex case-insensitive equivalente a %term%
        String regex = ".*" + Pattern.quote(term) + ".*";
        try (MongoCursor<Document> cursor = books
                .find(Filters.regex("content", regex, "i")).iterator()) {
            while (cursor.hasNext()) {
                res.add(toMap(cursor.next()));
            }
        }
        return res;
    }

    /** Cuenta el número total de libros */
    public int countBooks() {
        return (int) books.countDocuments();
    }

    /** Verifica conexión (readiness) */
    public boolean testConnection() {
        try {
            // Ping simple: contar 0 documentos con filtro imposible
            books.countDocuments(Filters.eq("_id", UUID.randomUUID().toString()));
            return true;
        } catch (Exception e) {
            System.err.println("[Search DatamartMongo] testConnection error: " + e.getMessage());
            return false;
        }
    }

    public void close() {
        try { client.close(); } catch (Exception ignored) {}
    }

    private Map<String, Object> toMap(Document doc) {
        Map<String, Object> row = new HashMap<>();
        row.put("book_id", doc.getInteger("book_id"));
        row.put("title", doc.getString("title"));
        row.put("author", doc.getString("author"));
        row.put("language", doc.getString("language"));
        row.put("year", doc.getInteger("year", -1));
        row.put("content", doc.getString("content"));
        row.put("content_hash", doc.getString("content_hash"));
        return row;
    }
}
