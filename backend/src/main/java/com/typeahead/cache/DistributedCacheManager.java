package com.typeahead.cache;

import com.typeahead.dto.QuerySuggestion;
import com.typeahead.hashing.ConsistentHashRing;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class DistributedCacheManager {

    private final ConsistentHashRing hashRing;
    private final Map<String, CacheNode> nodes = new ConcurrentHashMap<>();

    @Autowired
    public DistributedCacheManager(ConsistentHashRing hashRing) {
        this.hashRing = hashRing;
        // Initialize 4 nodes
        nodes.put("Node-1", new CacheNode("Node-1", 10000, 5));
        nodes.put("Node-2", new CacheNode("Node-2", 10000, 5));
        nodes.put("Node-3", new CacheNode("Node-3", 10000, 5));
        nodes.put("Node-4", new CacheNode("Node-4", 10000, 5));
    }

    public List<QuerySuggestion> get(String prefix) {
        return get(prefix, "basic");
    }

    public List<QuerySuggestion> get(String prefix, String ranking) {
        String targetNodeName = hashRing.getNode(prefix); // route by prefix to preserve locality
        if (targetNodeName == null) {
            return null;
        }
        CacheNode node = nodes.get(targetNodeName);
        if (node != null) {
            return node.get(prefix + ":" + ranking);
        }
        return null;
    }

    public void put(String prefix, List<QuerySuggestion> suggestions) {
        put(prefix, "basic", suggestions);
    }

    public void put(String prefix, String ranking, List<QuerySuggestion> suggestions) {
        String targetNodeName = hashRing.getNode(prefix);
        if (targetNodeName != null) {
            CacheNode node = nodes.get(targetNodeName);
            if (node != null) {
                node.put(prefix + ":" + ranking, suggestions);
            }
        }
    }

    /**
     * Invalidates all prefixes that could lead to this query in the cache.
     * E.g. query "iphone" invalidates "i", "ip", "iph", "ipho", "iphon", "iphone" for all rankings.
     */
    public void invalidatePrefixChain(String query) {
        if (query == null || query.trim().isEmpty()) {
            return;
        }
        String normalized = query.trim().toLowerCase();
        for (int i = 1; i <= normalized.length(); i++) {
            String prefix = normalized.substring(0, i);
            String targetNodeName = hashRing.getNode(prefix);
            if (targetNodeName != null) {
                CacheNode node = nodes.get(targetNodeName);
                if (node != null) {
                    node.invalidate(prefix + ":basic");
                    node.invalidate(prefix + ":recency");
                }
            }
        }
    }

    public Map<String, CacheNode> getNodes() {
        return nodes;
    }

    public long getTotalHits() {
        return nodes.values().stream().mapToLong(CacheNode::getHitCount).sum();
    }

    public long getTotalMisses() {
        return nodes.values().stream().mapToLong(CacheNode::getMissCount).sum();
    }

    public double getHitRatio() {
        long hits = getTotalHits();
        long misses = getTotalMisses();
        long total = hits + misses;
        if (total == 0) return 0.0;
        return (double) hits / total;
    }

    public int getCachedKeysCount() {
        return (int) nodes.values().stream().mapToLong(CacheNode::getSize).sum();
    }
}
