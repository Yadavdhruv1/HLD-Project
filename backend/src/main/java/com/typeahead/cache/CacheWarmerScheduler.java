package com.typeahead.cache;

import com.typeahead.dto.QuerySuggestion;
import com.typeahead.trie.TrieService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

@Component
@Slf4j
public class CacheWarmerScheduler {

    private final TrieService trieService;
    private final DistributedCacheManager cacheManager;

    @Autowired
    public CacheWarmerScheduler(TrieService trieService, DistributedCacheManager cacheManager) {
        this.trieService = trieService;
        this.cacheManager = cacheManager;
    }

    /**
     * Pre-populates the cache with top suggestions for common prefix paths.
     * Runs every 120 seconds in the background.
     */
    @Scheduled(initialDelay = 10000, fixedRate = 120000)
    public void warmCache() {
        if (!trieService.isLoaded()) {
            return;
        }

        log.info("Background Cache Warmer: Started warming common prefixes...");
        int warmedCount = 0;

        // 1. Warm single characters 'a' to 'z'
        for (char c = 'a'; c <= 'z'; c++) {
            String prefix = String.valueOf(c);
            List<QuerySuggestion> suggestions = trieService.search(prefix);
            if (!suggestions.isEmpty()) {
                cacheManager.put(prefix, suggestions);
                warmedCount++;
            }
        }

        // 2. Warm popular 2-character combinations
        List<String> popularPrefixes = Arrays.asList("ip", "ja", "am", "yo", "ch", "pl", "se", "ho");
        for (String prefix : popularPrefixes) {
            List<QuerySuggestion> suggestions = trieService.search(prefix);
            if (!suggestions.isEmpty()) {
                cacheManager.put(prefix, suggestions);
                warmedCount++;
            }
        }

        log.info("Background Cache Warmer: Successfully warmed {} prefixes in cache nodes.", warmedCount);
    }
}
