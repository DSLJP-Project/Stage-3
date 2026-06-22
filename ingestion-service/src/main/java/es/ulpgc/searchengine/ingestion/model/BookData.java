package es.ulpgc.searchengine.ingestion.model;

public class BookData {
    private final int id;
    private final String title;
    private final String author;
    private final String language;
    private final int year;
    private final String content;
    private final String contentHash;
    private final String sourceUrl;

    public BookData(int id, String title, String author, String language, int year, String content,
                    String contentHash, String sourceUrl) {
        this.id = id;
        this.title = title;
        this.author = author;
        this.language = language;
        this.year = year;
        this.content = content;
        this.contentHash = contentHash;
        this.sourceUrl = sourceUrl;
    }

    public int getId() { return id; }
    public String getTitle() { return title; }
    public String getAuthor() { return author; }
    public String getLanguage() { return language; }
    public int getYear() { return year; }
    public String getContent() { return content; }
    public String getContentHash() { return contentHash; }
    public String getSourceUrl() { return sourceUrl; }
}
