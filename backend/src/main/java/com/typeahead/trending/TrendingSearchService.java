package com.typeahead.trending;

import com.typeahead.dto.TrendingSuggestion;
import com.typeahead.metrics.MetricsTracker;
import com.typeahead.model.SearchQuery;
import com.typeahead.repository.SearchQueryRepository;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;

@Service
@Slf4j
public class TrendingSearchService {

    private final SearchQueryRepository repository;
    private final MetricsTracker metricsTracker;

    @Value("${trending.decay.lambda:0.0001}")
    private double lambda;

    @Autowired
    public TrendingSearchService(SearchQueryRepository repository, MetricsTracker metricsTracker) {
        this.repository = repository;
        this.metricsTracker = metricsTracker;
    }

    /**
     * DTO to represent trending candidates inside the Min-Heap.
     */
    @Data
    public static class TrendingCandidate implements Comparable<TrendingCandidate> {
        private String query;
        private double score;
        private long totalCount;
        private double recentCount;

        public TrendingCandidate(String query, double score, long totalCount, double recentCount) {
            this.query = query;
            this.score = score;
            this.totalCount = totalCount;
            this.recentCount = recentCount;
        }

        @Override
        public int compareTo(TrendingCandidate other) {
            return Double.compare(this.score, other.score); // min-heap order (ascending)
        }
    }

    public List<TrendingSuggestion> getTopTrending(int k) {
        long startTime = System.nanoTime();
        metricsTracker.incrementDbReads();

        // 1. Fetch top candidates from DB
        List<SearchQuery> candidates = repository.findAll(
            PageRequest.of(0, 200, Sort.by(Sort.Direction.DESC, "totalCount"))
        ).getContent();

        if (candidates.isEmpty()) {
            return Collections.emptyList();
        }

        LocalDateTime now = LocalDateTime.now();

        // 2. Compute decayed recent count for all candidates, tracking max metrics
        double maxTotalCount = 0;
        double maxDecayedRecent = 0;

        List<DecayedInfo> infoList = new ArrayList<>(candidates.size());
        for (SearchQuery sq : candidates) {
            long elapsedSeconds = Duration.between(sq.getLastUpdatedAt(), now).getSeconds();
            double decayedRecent = sq.getRecentCount() * Math.exp(-lambda * elapsedSeconds);

            maxTotalCount = Math.max(maxTotalCount, sq.getTotalCount());
            maxDecayedRecent = Math.max(maxDecayedRecent, decayedRecent);

            infoList.add(new DecayedInfo(sq.getQuery(), sq.getTotalCount(), decayedRecent));
        }

        // 3. Compute final normalized score and maintain top-k Min-Heap
        PriorityQueue<TrendingCandidate> minHeap = new PriorityQueue<>(k);
        for (DecayedInfo info : infoList) {
            double normalizedTotal = maxTotalCount == 0 ? 0.0 : (double) info.totalCount / maxTotalCount;
            double normalizedRecent = maxDecayedRecent == 0 ? 0.0 : info.decayedRecent / maxDecayedRecent;

            // Score: 70% popularity (totalCount) + 30% recency (decayedRecent)
            double score = 0.7 * normalizedTotal + 0.3 * normalizedRecent;
            // Round to 4 decimal places
            score = Math.round(score * 10000.0) / 10000.0;

            TrendingCandidate candidate = new TrendingCandidate(info.query, score, info.totalCount, info.decayedRecent);
            minHeap.offer(candidate);

            if (minHeap.size() > k) {
                minHeap.poll();
            }
        }

        // 4. Extract top-k results from Min-Heap and sort in descending order
        List<TrendingCandidate> sortedCandidates = new ArrayList<>(minHeap);
        sortedCandidates.sort(Collections.reverseOrder());

        List<TrendingSuggestion> result = new ArrayList<>(sortedCandidates.size());
        for (TrendingCandidate tc : sortedCandidates) {
            result.add(new TrendingSuggestion(tc.getQuery(), tc.getScore()));
        }

        return result;
    }

    private static class DecayedInfo {
        String query;
        long totalCount;
        double decayedRecent;

        DecayedInfo(String query, long totalCount, double decayedRecent) {
            this.query = query;
            this.totalCount = totalCount;
            this.decayedRecent = decayedRecent;
        }
    }
}
