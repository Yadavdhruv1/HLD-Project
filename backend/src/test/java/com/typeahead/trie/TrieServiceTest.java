package com.typeahead.trie;

import com.typeahead.dto.QuerySuggestion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

public class TrieServiceTest {

    private TrieService trieService;

    @BeforeEach
    public void setUp() {
        trieService = new TrieService();
    }

    @Test
    public void testPrefixSuggestions() {
        trieService.insertOrUpdate("iphone", 10000);
        trieService.insertOrUpdate("iphone 15", 85000);
        trieService.insertOrUpdate("iphone charger", 60000);
        trieService.insertOrUpdate("java tutorial", 40000);

        // Test searching with "iph"
        List<QuerySuggestion> suggestions = trieService.search("iph");
        assertEquals(3, suggestions.size());

        // Test ordering: should be "iphone 15" (85000), "iphone charger" (60000), "iphone" (10000)
        assertEquals("iphone 15", suggestions.get(0).getQuery());
        assertEquals(85000L, suggestions.get(0).getCount());

        assertEquals("iphone charger", suggestions.get(1).getQuery());
        assertEquals(60000L, suggestions.get(1).getCount());

        assertEquals("iphone", suggestions.get(2).getQuery());
        assertEquals(10000L, suggestions.get(2).getCount());
    }

    @Test
    public void testCaseInsensitivity() {
        trieService.insertOrUpdate("IPhone", 100);
        trieService.insertOrUpdate("iphone 15", 200);

        List<QuerySuggestion> suggestions = trieService.search("IPH");
        assertEquals(2, suggestions.size());
    }

    @Test
    public void testLimitToTop10() {
        // Insert 12 suggestions with same prefix
        for (int i = 1; i <= 12; i++) {
            trieService.insertOrUpdate("iphone test " + i, i * 10);
        }

        List<QuerySuggestion> suggestions = trieService.search("iphone");
        assertEquals(10, suggestions.size());

        // The top one should be "iphone test 12" with count 120
        assertEquals("iphone test 12", suggestions.get(0).getQuery());
        assertEquals(120L, suggestions.get(0).getCount());
        
        // The last one in top 10 should be "iphone test 3" with count 30
        assertEquals("iphone test 3", suggestions.get(9).getQuery());
        assertEquals(30L, suggestions.get(9).getCount());
    }

    @Test
    public void testEmptyAndNoMatches() {
        trieService.insertOrUpdate("iphone", 100);
        
        assertTrue(trieService.search("").isEmpty());
        assertTrue(trieService.search("  ").isEmpty());
        assertTrue(trieService.search(null).isEmpty());
        assertTrue(trieService.search("android").isEmpty());
    }

    @Test
    public void testRecencyAwareRanking() {
        // "iphone 15" has lower total count (700) but higher recency (700)
        trieService.insertOrUpdate("iphone 15", 700, 700);
        // "iphone" has higher total count (1000) but lower recency (50)
        trieService.insertOrUpdate("iphone", 1000, 50);

        // Under basic (popularity) ranking: "iphone" (1000) should be first
        List<QuerySuggestion> basicSuggestions = trieService.search("iph", "basic");
        assertEquals(2, basicSuggestions.size());
        assertEquals("iphone", basicSuggestions.get(0).getQuery());

        // Under recency-aware ranking:
        // Max total count = 1000, Max recent count = 700
        // iphone score = 0.7 * (1000/1000) + 0.3 * (50/700) = 0.7 + 0.0214 = 0.7214
        // iphone 15 score = 0.7 * (700/1000) + 0.3 * (700/700) = 0.49 + 0.30 = 0.7900
        // iphone 15 score (0.79) > iphone score (0.7214), so "iphone 15" should be first
        List<QuerySuggestion> recencySuggestions = trieService.search("iph", "recency");
        assertEquals(2, recencySuggestions.size());
        assertEquals("iphone 15", recencySuggestions.get(0).getQuery());
    }
}
