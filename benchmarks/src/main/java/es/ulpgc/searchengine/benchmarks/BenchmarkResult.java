package es.ulpgc.searchengine.benchmarks;

public class BenchmarkResult {
    public int bookId;
    public long ingestionTime;
    public long indexingTime;
    public long searchTime;
    public long totalTime;
    public boolean success;

    public BenchmarkResult(int bookId) {
        this.bookId = bookId;
    }
}
