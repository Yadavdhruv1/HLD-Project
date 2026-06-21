package com.typeahead.trie;

import com.typeahead.dto.QuerySuggestion;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TrieNode {

    private final Map<Character, TrieNode> children = new HashMap<>();
    private final List<QuerySuggestion> top10 = new ArrayList<>();
    private boolean isEndOfWord = false;

    public Map<Character, TrieNode> getChildren() {
        return children;
    }

    public List<QuerySuggestion> getTop10() {
        return top10;
    }

    public boolean isEndOfWord() {
        return isEndOfWord;
    }

    public void setEndOfWord(boolean endOfWord) {
        isEndOfWord = endOfWord;
    }

    /**
     * Updates the top 20 list at this node for a given query.
     */
    public void updateTop10(String query, long totalCount, double recentCount) {
        // Find if this query is already in the list
        int existingIndex = -1;
        for (int i = 0; i < top10.size(); i++) {
            if (top10.get(i).getQuery().equals(query)) {
                existingIndex = i;
                break;
            }
        }

        if (existingIndex != -1) {
            // Update count and recent count
            QuerySuggestion suggestion = top10.get(existingIndex);
            suggestion.setCount(totalCount);
            suggestion.setRecentCount(recentCount);
        } else {
            // Add new suggestion
            top10.add(new QuerySuggestion(query, totalCount, recentCount, (double) totalCount));
        }

        // Sort descending by count, then alphabetically if counts are equal
        top10.sort((a, b) -> {
            int cmp = Long.compare(b.getCount(), a.getCount());
            if (cmp == 0) {
                return a.getQuery().compareTo(b.getQuery());
            }
            return cmp;
        });

        // Prune to top 20 to allow a larger pool for on-the-fly recency re-ranking
        if (top10.size() > 20) {
            top10.remove(20);
        }
    }
}
