package com.typeahead.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.typeahead.dto.QuerySuggestion;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;

public class CacheNode {

    private final String name;
    private final Cache<String, List<QuerySuggestion>> cache;
    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();

    public CacheNode(String name, int maxSize, int expireMinutes) {
        this.name = name;
        this.cache = Caffeine.newBuilder()
            .maximumSize(maxSize)
            .expireAfterWrite(Duration.ofMinutes(expireMinutes))
            .recordStats()
            .build();
    }

    public String getName() {
        return name;
    }

    public List<QuerySuggestion> get(String prefix) {
        List<QuerySuggestion> result = cache.getIfPresent(prefix);
        if (result != null) {
            hits.increment();
        } else {
            misses.increment();
        }
        return result;
    }

    public void put(String prefix, List<QuerySuggestion> suggestions) {
        cache.put(prefix, suggestions);
    }

    public void invalidate(String prefix) {
        cache.invalidate(prefix);
    }

    public void clear() {
        cache.invalidateAll();
    }

    public long getHitCount() {
        return hits.sum();
    }

    public long getMissCount() {
        return misses.sum();
    }

    public long getSize() {
        return cache.estimatedSize();
    }

    public CacheStats getStats() {
        return cache.stats();
    }
}
