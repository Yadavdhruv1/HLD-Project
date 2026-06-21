package com.typeahead.buffer;

import com.typeahead.dto.QuerySuggestion;
import com.typeahead.model.SearchQuery;
import com.typeahead.repository.SearchQueryRepository;
import com.typeahead.trie.TrieService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class BatchWriterIntegrationTest {

    @Autowired
    private SearchBuffer searchBuffer;

    @Autowired
    private BatchWriterScheduler batchWriterScheduler;

    @Autowired
    private SearchQueryRepository repository;

    @Autowired
    private TrieService trieService;

    @BeforeEach
    public void cleanUp() {
        searchBuffer.getQueue().clear();
        searchBuffer.clearWal();
        repository.deleteAll();
        trieService.clear();
    }

    @Test
    @Transactional
    public void testBufferAndFlushWorkflow() {
        // 1. Submit queries to buffer
        assertTrue(searchBuffer.submit("iphone"));
        assertTrue(searchBuffer.submit("iphone"));
        assertTrue(searchBuffer.submit("java"));

        assertEquals(3, searchBuffer.getQueueSize());

        // Verify WAL file exists and contains entries
        File walFile = new File("wal.log");
        assertTrue(walFile.exists());
        assertTrue(walFile.length() > 0);

        // 2. Manually trigger periodic flush
        batchWriterScheduler.periodicFlush();

        // Queue should be empty now
        assertEquals(0, searchBuffer.getQueueSize());

        // WAL file should be truncated/recreated empty
        assertEquals(0, walFile.length());

        // 3. Verify Database writes
        Optional<SearchQuery> iphoneQuery = repository.findByQuery("iphone");
        assertTrue(iphoneQuery.isPresent());
        assertEquals(2L, iphoneQuery.get().getTotalCount());
        assertEquals(2.0, iphoneQuery.get().getRecentCount());

        Optional<SearchQuery> javaQuery = repository.findByQuery("java");
        assertTrue(javaQuery.isPresent());
        assertEquals(1L, javaQuery.get().getTotalCount());

        // 4. Verify Incremental Trie updates
        List<QuerySuggestion> suggestions = trieService.search("iph");
        assertEquals(1, suggestions.size());
        assertEquals("iphone", suggestions.get(0).getQuery());
        assertEquals(2L, suggestions.get(0).getCount());

        List<QuerySuggestion> javaSuggestions = trieService.search("jav");
        assertEquals(1, javaSuggestions.size());
        assertEquals("java", javaSuggestions.get(0).getQuery());
    }

    @Test
    public void testWalReplayRecovery() {
        // 1. Write manual entries to WAL by submitting to searchBuffer (this updates WAL and Queue)
        searchBuffer.submit("recovered query");
        searchBuffer.submit("recovered query");

        // Force queue clear to simulate application crash before flush
        searchBuffer.getQueue().clear();
        assertEquals(0, searchBuffer.getQueueSize());

        // 2. Simulate startup replay by calling the hook
        batchWriterScheduler.onStartup();

        // 3. Verify recovered query exists in the database
        Optional<SearchQuery> query = repository.findByQuery("recovered query");
        assertTrue(query.isPresent());
        assertEquals(2L, query.get().getTotalCount());

        // 4. Verify it was loaded in the Trie
        List<QuerySuggestion> suggestions = trieService.search("rec");
        assertEquals(1, suggestions.size());
        assertEquals("recovered query", suggestions.get(0).getQuery());
    }
}
