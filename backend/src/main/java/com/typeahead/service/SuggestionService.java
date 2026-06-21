package com.typeahead.service;

import com.typeahead.cache.DistributedCacheManager;
import com.typeahead.dto.QuerySuggestion;
import com.typeahead.metrics.MetricsTracker;
import com.typeahead.trie.TrieService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

@Service
@Slf4j
public class SuggestionService {

    private final DistributedCacheManager cacheManager;
    private final TrieService trieService;
    private final MetricsTracker metricsTracker;

    @Autowired
    public SuggestionService(DistributedCacheManager cacheManager,
                             TrieService trieService,
                             MetricsTracker metricsTracker) {
        this.cacheManager = cacheManager;
        this.trieService = trieService;
        this.metricsTracker = metricsTracker;
    }

    public List<QuerySuggestion> getSuggestions(String prefix) {
        return getSuggestions(prefix, "basic");
    }

    /**
     * Retrieves autocomplete suggestions for the given prefix.
     * Flow: Distributed Cache -> (Miss) -> In-Memory Trie -> Update Cache -> Return.
     */
    public List<QuerySuggestion> getSuggestions(String prefix, String ranking) {
        long startTime = System.nanoTime();
        try {
            if (prefix == null || prefix.trim().isEmpty()) {
                return Collections.emptyList();
            }
            String normalizedPrefix = prefix.trim().toLowerCase();

            // 1. Try fetching from Distributed Cache Node (routed via consistent hashing ring)
            List<QuerySuggestion> cached = cacheManager.get(normalizedPrefix, ranking);
            if (cached != null) {
                metricsTracker.incrementCacheHits();
                return cached;
            }

            // 2. Cache Miss -> Query In-Memory Trie (thread-safe, O(L) complexity)
            metricsTracker.incrementCacheMisses();
            List<QuerySuggestion> suggestions = trieService.search(normalizedPrefix, ranking);

            // 3. Pre-populate Cache for subsequent requests
            cacheManager.put(normalizedPrefix, ranking, suggestions);

            return suggestions;
        } finally {
            metricsTracker.recordSuggestLatency(System.nanoTime() - startTime);
        }
    }
}
