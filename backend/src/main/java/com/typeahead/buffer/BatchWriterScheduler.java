package com.typeahead.buffer;

import com.typeahead.cache.DistributedCacheManager;
import com.typeahead.metrics.MetricsTracker;
import com.typeahead.model.SearchQuery;
import com.typeahead.repository.SearchQueryRepository;
import com.typeahead.trie.TrieService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

@Component
@Slf4j
public class BatchWriterScheduler {

    private final SearchBuffer searchBuffer;
    private final SearchQueryRepository repository;
    private final JdbcTemplate jdbcTemplate;
    private final TrieService trieService;
    private final DistributedCacheManager cacheManager;
    private final MetricsTracker metricsTracker;

    @Value("${trending.decay.lambda:0.0001}")
    private double lambda;

    @Autowired
    public BatchWriterScheduler(SearchBuffer searchBuffer,
                                SearchQueryRepository repository,
                                JdbcTemplate jdbcTemplate,
                                TrieService trieService,
                                DistributedCacheManager cacheManager,
                                MetricsTracker metricsTracker) {
        this.searchBuffer = searchBuffer;
        this.repository = repository;
        this.jdbcTemplate = jdbcTemplate;
        this.trieService = trieService;
        this.cacheManager = cacheManager;
        this.metricsTracker = metricsTracker;
    }

    @PostConstruct
    public void onStartup() {
        log.info("Checking for WAL recovery on startup...");
        List<String> recovered = searchBuffer.replayWal();
        if (!recovered.isEmpty()) {
            log.info("Recovered {} queries from WAL. Flushing to database...", recovered.size());
            flushBatch(recovered);
        }
    }

    /**
     * Periodic scheduled batch write running every 5 seconds.
     */
    @Scheduled(fixedRate = 5000)
    public void periodicFlush() {
        drainedAndFlush();
    }

    /**
     * Checks if queue has hit the size threshold (e.g. >= 100 queries) to trigger an early flush.
     */
    public void checkSizeAndFlush() {
        if (searchBuffer.getQueueSize() >= 100) {
            drainedAndFlush();
        }
    }

    private synchronized void drainedAndFlush() {
        List<String> queries = new ArrayList<>();
        searchBuffer.getQueue().drainTo(queries);

        if (queries.isEmpty()) {
            return;
        }

        log.info("Drained {} queries from buffer queue. Executing batch update...", queries.size());
        flushBatch(queries);
    }

    @Transactional
    public void flushBatch(List<String> queries) {
        long startTime = System.nanoTime();
        if (queries == null || queries.isEmpty()) return;

        // 1. Aggregate counts
        Map<String, Long> aggregates = new HashMap<>();
        for (String q : queries) {
            aggregates.put(q, aggregates.getOrDefault(q, 0L) + 1L);
        }

        try {
            LocalDateTime now = LocalDateTime.now();
            List<String> queryStrings = new ArrayList<>(aggregates.keySet());

            // 2. Fetch existing records
            metricsTracker.incrementDbReads();
            List<SearchQuery> existingQueries = repository.findAllByQueries(queryStrings);
            Map<String, SearchQuery> existingMap = new HashMap<>();
            for (SearchQuery sq : existingQueries) {
                existingMap.put(sq.getQuery(), sq);
            }

            List<SearchQuery> toUpdate = new ArrayList<>();
            List<SearchQuery> toInsert = new ArrayList<>();

            for (Map.Entry<String, Long> entry : aggregates.entrySet()) {
                String q = entry.getKey();
                long increment = entry.getValue();

                if (existingMap.containsKey(q)) {
                    SearchQuery sq = existingMap.get(q);
                    // Compute decay on write
                    long secondsElapsed = Duration.between(sq.getLastUpdatedAt(), now).getSeconds();
                    double decayedRecent = sq.getRecentCount() * Math.exp(-lambda * secondsElapsed);

                    sq.setTotalCount(sq.getTotalCount() + increment);
                    sq.setRecentCount(decayedRecent + increment);
                    sq.setLastUpdatedAt(now);
                    toUpdate.add(sq);
                } else {
                    SearchQuery sq = SearchQuery.builder()
                        .query(q)
                        .totalCount(increment)
                        .recentCount((double) increment)
                        .lastUpdatedAt(now)
                        .build();
                    toInsert.add(sq);
                }
            }

            // 3. Batch DB Updates via JdbcTemplate
            if (!toUpdate.isEmpty()) {
                String updateSql = "UPDATE search_query SET total_count = ?, recent_count = ?, last_updated_at = ? WHERE id = ?";
                jdbcTemplate.batchUpdate(updateSql, new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int i) throws SQLException {
                        SearchQuery sq = toUpdate.get(i);
                        ps.setLong(1, sq.getTotalCount());
                        ps.setDouble(2, sq.getRecentCount());
                        ps.setTimestamp(3, Timestamp.valueOf(sq.getLastUpdatedAt()));
                        ps.setLong(4, sq.getId());
                    }

                    @Override
                    public int getBatchSize() {
                        return toUpdate.size();
                    }
                });
                metricsTracker.incrementDbWrites();
            }

            // 4. Batch DB Inserts via JdbcTemplate
            if (!toInsert.isEmpty()) {
                String insertSql = "INSERT INTO search_query (query, total_count, recent_count, last_updated_at) VALUES (?, ?, ?, ?)";
                jdbcTemplate.batchUpdate(insertSql, new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int i) throws SQLException {
                        SearchQuery sq = toInsert.get(i);
                        ps.setString(1, sq.getQuery());
                        ps.setLong(2, sq.getTotalCount());
                        ps.setDouble(3, sq.getRecentCount());
                        ps.setTimestamp(4, Timestamp.valueOf(sq.getLastUpdatedAt()));
                    }

                    @Override
                    public int getBatchSize() {
                        return toInsert.size();
                    }
                });
                metricsTracker.incrementDbWrites();
            }

            // 5. Incremental Trie Updates
            for (SearchQuery sq : toUpdate) {
                trieService.insertOrUpdate(sq.getQuery(), sq.getTotalCount(), sq.getRecentCount());
            }
            for (SearchQuery sq : toInsert) {
                trieService.insertOrUpdate(sq.getQuery(), sq.getTotalCount(), sq.getRecentCount());
            }

            // 6. Targeted Cache Invalidation
            for (String q : aggregates.keySet()) {
                cacheManager.invalidatePrefixChain(q);
            }

            // 7. Clear WAL
            searchBuffer.clearWal();
            log.info("Batch flush completed successfully: {} updated, {} inserted in {} ms",
                toUpdate.size(), toInsert.size(), (System.nanoTime() - startTime) / 1_000_000);

        } catch (Exception e) {
            log.error("Batch write failed! WAL remains intact for retry. Error: {}", e.getMessage(), e);
        }
    }
}
