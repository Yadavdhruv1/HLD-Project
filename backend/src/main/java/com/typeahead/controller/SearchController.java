package com.typeahead.controller;

import com.typeahead.buffer.SearchBuffer;
import com.typeahead.cache.DistributedCacheManager;
import com.typeahead.dto.QuerySuggestion;
import com.typeahead.dto.TrendingSuggestion;
import com.typeahead.hashing.ConsistentHashRing;
import com.typeahead.metrics.MetricsTracker;
import com.typeahead.service.SuggestionService;
import com.typeahead.trie.TrieService;
import com.typeahead.trending.TrendingSearchService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*") // Allows local React app to communicate without CORS issues
public class SearchController {

    private final SuggestionService suggestionService;
    private final SearchBuffer searchBuffer;
    private final TrendingSearchService trendingSearchService;
    private final MetricsTracker metricsTracker;
    private final DistributedCacheManager cacheManager;
    private final ConsistentHashRing hashRing;
    private final TrieService trieService;

    @Autowired
    public SearchController(SuggestionService suggestionService,
                            SearchBuffer searchBuffer,
                            TrendingSearchService trendingSearchService,
                            MetricsTracker metricsTracker,
                            DistributedCacheManager cacheManager,
                            ConsistentHashRing hashRing,
                            TrieService trieService) {
        this.suggestionService = suggestionService;
        this.searchBuffer = searchBuffer;
        this.trendingSearchService = trendingSearchService;
        this.metricsTracker = metricsTracker;
        this.cacheManager = cacheManager;
        this.hashRing = hashRing;
        this.trieService = trieService;
    }

    /**
     * GET /suggest?q=<prefix>&ranking=<basic|recency>
     * Returns up to 10 autocomplete suggestions matching the prefix.
     */
    @GetMapping("/suggest")
    public ResponseEntity<List<QuerySuggestion>> suggest(
            @RequestParam(value = "q", required = false, defaultValue = "") String query,
            @RequestParam(value = "ranking", required = false, defaultValue = "basic") String ranking) {
        if (query.trim().isEmpty()) {
            return ResponseEntity.ok(Collections.emptyList());
        }
        List<QuerySuggestion> suggestions = suggestionService.getSuggestions(query, ranking);
        return ResponseEntity.ok(suggestions);
    }

    /**
     * POST /search
     * Submits a query search event, enqueuing it in the buffer and writing to the WAL.
     */
    @PostMapping("/search")
    public ResponseEntity<Map<String, String>> search(@RequestBody Map<String, String> request) {
        String query = request.get("query");
        Map<String, String> response = new HashMap<>();

        if (query == null || query.trim().isEmpty()) {
            response.put("error", "Query cannot be empty");
            return ResponseEntity.badRequest().body(response);
        }

        boolean enqueued = searchBuffer.submit(query);
        if (enqueued) {
            response.put("message", "Searched");
            return ResponseEntity.ok(response);
        } else {
            response.put("error", "Server busy (backpressure triggered)");
            return ResponseEntity.status(503).body(response);
        }
    }

    /**
     * GET /trending
     * Returns top trending searches calculated using exponential decay scoring.
     */
    @GetMapping("/trending")
    public ResponseEntity<List<TrendingSuggestion>> trending(@RequestParam(value = "limit", required = false, defaultValue = "10") int limit) {
        List<TrendingSuggestion> trending = trendingSearchService.getTopTrending(limit);
        return ResponseEntity.ok(trending);
    }

    /**
     * GET /metrics
     * Returns runtime latency, cache statistics, and DB execution counters.
     */
    @GetMapping("/metrics")
    public ResponseEntity<Map<String, Object>> metrics() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("avgLatency", metricsTracker.getAvgLatencyMs());
        response.put("p95Latency", metricsTracker.getP95LatencyMs());
        response.put("cacheHitRate", Math.round(metricsTracker.getCacheHitRate() * 100.0) / 100.0);
        response.put("dbReads", metricsTracker.getDbReads());
        response.put("dbWrites", metricsTracker.getDbWrites());
        response.put("queueSize", searchBuffer.getQueueSize());
        response.put("cachedKeys", cacheManager.getCachedKeysCount());
        return ResponseEntity.ok(response);
    }

    /**
     * GET /cache/debug?prefix=<prefix>
     * Displays which cache node is responsible for storing a prefix and its current hit status.
     */
    @GetMapping("/cache/debug")
    public ResponseEntity<Map<String, Object>> cacheDebug(@RequestParam("prefix") String prefix) {
        Map<String, Object> response = new LinkedHashMap<>();
        String targetNode = hashRing.getNode(prefix);
        long hashValue = hashRing.hash(prefix);
        boolean hit = false;

        if (targetNode != null) {
            var node = cacheManager.getNodes().get(targetNode);
            if (node != null) {
                // Peek without recording hit/miss stats
                hit = node.getStats().requestCount() > 0 && node.get(prefix) != null;
            }
        }

        response.put("prefix", prefix);
        response.put("cacheNode", targetNode != null ? targetNode : "None");
        response.put("hash", hashValue);
        response.put("hit", hit);
        return ResponseEntity.ok(response);
    }

    /**
     * GET /ring/debug
     * Returns distribution of virtual node segments on the consistent hashing ring.
     */
    @GetMapping("/ring/debug")
    public ResponseEntity<Map<String, Object>> ringDebug() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("virtualNodesPerPhysical", hashRing.getVirtualNodesPerPhysical());
        response.put("distribution", hashRing.getDistribution());
        response.put("totalKeys", cacheManager.getCachedKeysCount());
        return ResponseEntity.ok(response);
    }

    /**
     * GET /health
     * Returns the operational readiness status of critical components.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> response = new LinkedHashMap<>();
        boolean dbUp = false;
        try {
            jdbcTemplateQueryHealthCheck();
            dbUp = true;
        } catch (Exception e) {
            // DB down
        }

        response.put("database", dbUp ? "UP" : "DOWN");
        response.put("cache", "UP");
        response.put("trieLoaded", trieService.isLoaded());
        response.put("walEnabled", true);
        return ResponseEntity.ok(response);
    }

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private void jdbcTemplateQueryHealthCheck() {
        jdbcTemplate.queryForObject("SELECT 1", Integer.class);
    }
}
