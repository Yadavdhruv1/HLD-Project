package com.typeahead.trie;

import com.typeahead.dto.QuerySuggestion;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Service
public class TrieService {

    private final TrieNode root = new TrieNode();
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
    private boolean loaded = false;

    public boolean isLoaded() {
        return loaded;
    }

    public void setLoaded(boolean loaded) {
        this.loaded = loaded;
    }

    public void insertOrUpdate(String query, long totalCount) {
        insertOrUpdate(query, totalCount, (double) totalCount);
    }

    /**
     * Incremental insertion or update. Inserts the characters of the query into the Trie,
     * and updates the pre-sorted top 20 suggestion list on each node along the path.
     */
    public void insertOrUpdate(String query, long totalCount, double recentCount) {
        if (query == null || query.trim().isEmpty()) {
            return;
        }
        String normalized = query.trim().toLowerCase();

        rwLock.writeLock().lock();
        try {
            TrieNode current = root;
            current.updateTop10(query, totalCount, recentCount); // update top10 at root as well

            for (int i = 0; i < normalized.length(); i++) {
                char ch = normalized.charAt(i);
                current = current.getChildren().computeIfAbsent(ch, k -> new TrieNode());
                current.updateTop10(query, totalCount, recentCount);
            }
            current.setEndOfWord(true);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    public List<QuerySuggestion> search(String prefix) {
        return search(prefix, "basic");
    }

    /**
     * Finds the node matching the prefix and returns its sorted top 10 suggestions based on ranking mode.
     * Completes in O(L) time where L is the length of the prefix.
     */
    public List<QuerySuggestion> search(String prefix, String ranking) {
        if (prefix == null || prefix.trim().isEmpty()) {
            return Collections.emptyList();
        }
        String normalized = prefix.trim().toLowerCase();

        rwLock.readLock().lock();
        try {
            TrieNode current = root;
            for (int i = 0; i < normalized.length(); i++) {
                char ch = normalized.charAt(i);
                current = current.getChildren().get(ch);
                if (current == null) {
                    return Collections.emptyList();
                }
            }

            // Create a deep copy of the top suggestions list and calculate score based on ranking mode
            List<QuerySuggestion> copy = new ArrayList<>(current.getTop10().size());
            double maxTotalCount = 0;
            double maxRecentCount = 0;

            for (QuerySuggestion suggestion : current.getTop10()) {
                double recentVal = suggestion.getRecentCount() != null ? suggestion.getRecentCount() : 0.0;
                maxTotalCount = Math.max(maxTotalCount, suggestion.getCount());
                maxRecentCount = Math.max(maxRecentCount, recentVal);
            }

            for (QuerySuggestion suggestion : current.getTop10()) {
                double recentVal = suggestion.getRecentCount() != null ? suggestion.getRecentCount() : 0.0;
                double score;
                if ("recency".equalsIgnoreCase(ranking)) {
                    double normTotal = maxTotalCount == 0 ? 0.0 : (double) suggestion.getCount() / maxTotalCount;
                    double normRecent = maxRecentCount == 0 ? 0.0 : recentVal / maxRecentCount;
                    score = 0.7 * normTotal + 0.3 * normRecent;
                } else {
                    score = (double) suggestion.getCount();
                }
                // Round score to 4 decimal places
                score = Math.round(score * 10000.0) / 10000.0;
                copy.add(new QuerySuggestion(suggestion.getQuery(), suggestion.getCount(), recentVal, score));
            }

            // Sort descending by score, then alphabetically if equal
            copy.sort((a, b) -> {
                int cmp = Double.compare(b.getScore(), a.getScore());
                if (cmp == 0) {
                    return a.getQuery().compareTo(b.getQuery());
                }
                return cmp;
            });

            // Limit to top 10 suggestions
            if (copy.size() > 10) {
                return new ArrayList<>(copy.subList(0, 10));
            }
            return copy;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * Clears the Trie.
     */
    public void clear() {
        rwLock.writeLock().lock();
        try {
            root.getChildren().clear();
            root.getTop10().clear();
            loaded = false;
        } finally {
            rwLock.writeLock().unlock();
        }
    }
}
