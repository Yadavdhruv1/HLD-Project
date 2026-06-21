package com.typeahead.buffer;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;

@Component
@Slf4j
public class SearchBuffer {

    private static final String WAL_PATH = "wal.log";
    private final LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>(10000);
    private BufferedWriter walWriter;
    private File walFile;

    @PostConstruct
    public void init() {
        try {
            walFile = new File(WAL_PATH);
            if (!walFile.exists()) {
                walFile.createNewFile();
            }
            // Append mode, auto flush on write
            walWriter = new BufferedWriter(new FileWriter(walFile, true));
            log.info("Initialized WAL file at: {}", walFile.getAbsolutePath());
        } catch (IOException e) {
            log.error("Failed to initialize WAL file: {}", e.getMessage(), e);
        }
    }

    public boolean submit(String query) {
        if (query == null || query.trim().isEmpty()) {
            return false;
        }
        String cleanQuery = query.trim();

        // 1. Write to WAL first (durability)
        writeToWal(cleanQuery);

        // 2. Offer to queue (backpressure support: returns false if queue full)
        boolean offered = queue.offer(cleanQuery);
        if (!offered) {
            log.warn("SearchBuffer queue is full, dropping query (Backpressure): {}", cleanQuery);
        }
        return offered;
    }

    private synchronized void writeToWal(String query) {
        if (walWriter == null) return;
        try {
            walWriter.write(query);
            walWriter.newLine();
            walWriter.flush();
        } catch (IOException e) {
            log.error("WAL write failed for query '{}': {}", query, e.getMessage());
        }
    }

    public synchronized List<String> replayWal() {
        if (walFile == null || !walFile.exists()) {
            return Collections.emptyList();
        }
        List<String> queries = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(walFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    queries.add(line.trim());
                }
            }
            log.info("Replayed {} entries from WAL", queries.size());
        } catch (IOException e) {
            log.error("Failed to read WAL during replay: {}", e.getMessage());
        }
        return queries;
    }

    public synchronized void clearWal() {
        try {
            if (walWriter != null) {
                walWriter.close();
            }
            if (walFile != null && walFile.exists()) {
                Files.delete(Paths.get(walFile.toURI()));
                log.info("Deleted WAL log after database flush.");
            }
            walFile = new File(WAL_PATH);
            walFile.createNewFile();
            walWriter = new BufferedWriter(new FileWriter(walFile, true));
        } catch (IOException e) {
            log.error("Failed to clear WAL file: {}", e.getMessage());
        }
    }

    public LinkedBlockingQueue<String> getQueue() {
        return queue;
    }

    public int getQueueSize() {
        return queue.size();
    }

    @PreDestroy
    public void cleanup() {
        try {
            if (walWriter != null) {
                walWriter.close();
            }
        } catch (IOException e) {
            log.error("Failed to close WAL writer during destroy: {}", e.getMessage());
        }
    }
}
