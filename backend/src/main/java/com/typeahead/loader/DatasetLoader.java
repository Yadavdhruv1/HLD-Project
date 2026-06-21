package com.typeahead.loader;

import com.typeahead.model.SearchQuery;
import com.typeahead.repository.SearchQueryRepository;
import com.typeahead.trie.TrieService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;

@Component
@Slf4j
public class DatasetLoader implements CommandLineRunner {

    private final SearchQueryRepository repository;
    private final JdbcTemplate jdbcTemplate;
    private final TrieService trieService;

    @Autowired
    public DatasetLoader(SearchQueryRepository repository,
                         JdbcTemplate jdbcTemplate,
                         TrieService trieService) {
        this.repository = repository;
        this.jdbcTemplate = jdbcTemplate;
        this.trieService = trieService;
    }

    @Override
    public void run(String... args) throws Exception {
        long totalCount = repository.count();
        log.info("Database contains {} search queries.", totalCount);

        if (totalCount == 0) {
            log.info("Database is empty. Generating 100,000 sample queries...");
            long genStart = System.currentTimeMillis();
            List<SearchQuery> sampleQueries = generateSampleDataset();
            log.info("Generated 100,000 queries in-memory in {} ms. Loading into DB...", (System.currentTimeMillis() - genStart));

            long dbStart = System.currentTimeMillis();
            bulkInsert(sampleQueries);
            log.info("Successfully loaded 100,000 queries into DB in {} ms.", (System.currentTimeMillis() - dbStart));
        }

        // Load all queries from database into Trie
        log.info("Pre-loading all queries into In-Memory Trie index...");
        long trieStart = System.currentTimeMillis();

        // Stream or fetch queries in pages to avoid OutOfMemory on huge datasets
        // Since it's 100k entries, loading them in chunks or list is fine
        List<SearchQuery> allQueries = repository.findAll();
        for (SearchQuery sq : allQueries) {
            trieService.insertOrUpdate(sq.getQuery(), sq.getTotalCount(), sq.getRecentCount());
        }
        trieService.setLoaded(true);
        log.info("Successfully populated Trie with {} entries in {} ms.", allQueries.size(), (System.currentTimeMillis() - trieStart));
    }

    private List<SearchQuery> generateSampleDataset() {
        List<String> actions = Arrays.asList(
            "buy", "how to learn", "best", "latest", "cheap", "free", "review of",
            "tutorial on", "learn", "download", "where to find", "what is",
            "simple", "easy", "advanced", "guide to", "top", "new", "deal on", "compare"
        );

        List<String> items = Arrays.asList(
            "iphone", "samsung galaxy", "google pixel", "ipad", "macbook pro", "dell laptop",
            "java programming", "python coding", "javascript", "react js", "c++ guide",
            "caffeine caffeine", "spring boot", "consistent hashing", "postgresql", "redis cache",
            "docker container", "kubernetes cluster", "chatgpt ai", "openai models", "nike shoes",
            "adidas sneakers", "rolex watches", "sony camera", "noise headphones", "mechanical keyboard",
            "gaming monitor", "coffee machine", "electric cars", "tesla roadster", "bitcoin trading",
            "ethereum wallet", "stock markets", "local weather", "flight tickets", "hotel bookings",
            "italian restaurants", "latest movies", "trending songs", "scifi books"
        );

        List<String> modifiers = new ArrayList<>();
        for (int i = 1; i <= 150; i++) {
            modifiers.add("part " + i);
            modifiers.add("module " + i);
            modifiers.add("step " + i);
            modifiers.add("class " + i);
        }

        List<SearchQuery> sampleQueries = new ArrayList<>(100000);
        LocalDateTime now = LocalDateTime.now();
        Random rand = new Random(42); // Seeded for consistency

        int count = 0;
        for (String action : actions) {
            for (String item : items) {
                for (String mod : modifiers) {
                    if (count >= 100000) break;

                    String query = action + " " + item + " " + mod;
                    long total = rand.nextInt(100000) + 10;
                    // recent is smaller or equal to total, with some bias for recency
                    double recent = rand.nextInt((int) Math.min(total, 10000)) + 1.0;

                    SearchQuery sq = SearchQuery.builder()
                        .query(query)
                        .totalCount(total)
                        .recentCount(recent)
                        .lastUpdatedAt(now.minusSeconds(rand.nextInt(86400 * 3))) // scattered over the last 3 days
                        .build();

                    sampleQueries.add(sq);
                    count++;
                }
            }
        }

        // Add specific base queries as requested in prompt to ensure they exist explicitly
        sampleQueries.add(new SearchQuery(null, "iphone", 100000L, 50000.0, now));
        sampleQueries.add(new SearchQuery(null, "iphone 15", 85000L, 40000.0, now));
        sampleQueries.add(new SearchQuery(null, "iphone charger", 60000L, 30000.0, now));
        sampleQueries.add(new SearchQuery(null, "java tutorial", 40000L, 20000.0, now));

        return sampleQueries;
    }

    private void bulkInsert(List<SearchQuery> queries) {
        String sql = "INSERT INTO search_query (query, total_count, recent_count, last_updated_at) VALUES (?, ?, ?, ?)";
        int batchSize = 2000;

        for (int i = 0; i < queries.size(); i += batchSize) {
            final List<SearchQuery> subList = queries.subList(i, Math.min(i + batchSize, queries.size()));

            jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement ps, int j) throws SQLException {
                    SearchQuery sq = subList.get(j);
                    ps.setString(1, sq.getQuery());
                    ps.setLong(2, sq.getTotalCount());
                    ps.setDouble(3, sq.getRecentCount());
                    ps.setTimestamp(4, Timestamp.valueOf(sq.getLastUpdatedAt()));
                }

                @Override
                public int getBatchSize() {
                    return subList.size();
                }
            });
        }
    }
}
