package es.ulpgc.searchengine.crawler;

import java.nio.file.Path;
import java.nio.file.Paths;

public class DatalakePartitioner {

    private final int crawlerId;
    private final int numCrawlers;

    public DatalakePartitioner(int crawlerId, int numCrawlers) {
        this.crawlerId = crawlerId;
        this.numCrawlers = numCrawlers;
    }

    /**
     * Devuelve true si este crawler es responsable del bookId
     */
    public boolean owns(int bookId) {
        return Math.floorMod(bookId, numCrawlers) == crawlerId;
    }

    /**
     * Ruta física de la partición de este crawler
     */
    public Path getPartitionPath(String datalakeBasePath) {
        return Paths.get(datalakeBasePath, "partition-" + crawlerId);
    }

    public int getCrawlerId() {
        return crawlerId;
    }

    public int getNumCrawlers() {
        return numCrawlers;
    }
}
