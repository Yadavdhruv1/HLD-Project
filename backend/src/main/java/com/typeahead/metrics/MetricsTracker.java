package com.typeahead.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

@Component
public class MetricsTracker {

    private final LongAdder dbReads = new LongAdder();
    private final LongAdder dbWrites = new LongAdder();
    private final LongAdder cacheHits = new LongAdder();
    private final LongAdder cacheMisses = new LongAdder();

    private final ConcurrentLinkedQueue<Long> latencyWindow = new ConcurrentLinkedQueue<>();
    private static final int MAX_WINDOW_SIZE = 1000;

    // Micrometer metrics
    private final Timer suggestTimer;
    private final Counter dbReadsCounter;
    private final Counter dbWritesCounter;
    private final Counter cacheHitsCounter;
    private final Counter cacheMissesCounter;

    @Autowired
    public MetricsTracker(MeterRegistry registry) {
        this.suggestTimer = Timer.builder("typeahead.suggest.latency")
            .description("Time taken for typeahead suggestions")
            .register(registry);

        this.dbReadsCounter = Counter.builder("typeahead.db.reads")
            .description("Count of DB read operations")
            .register(registry);

        this.dbWritesCounter = Counter.builder("typeahead.db.writes")
            .description("Count of DB write operations")
            .register(registry);

        this.cacheHitsCounter = Counter.builder("typeahead.cache.hits")
            .description("Count of cache hits")
            .register(registry);

        this.cacheMissesCounter = Counter.builder("typeahead.cache.misses")
            .description("Count of cache misses")
            .register(registry);
    }

    public void recordSuggestLatency(long durationNs) {
        suggestTimer.record(durationNs, TimeUnit.NANOSECONDS);
        latencyWindow.offer(durationNs);
        while (latencyWindow.size() > MAX_WINDOW_SIZE) {
            latencyWindow.poll();
        }
    }

    public void incrementDbReads() {
        dbReads.increment();
        dbReadsCounter.increment();
    }

    public void incrementDbWrites() {
        dbWrites.increment();
        dbWritesCounter.increment();
    }

    public void incrementCacheHits() {
        cacheHits.increment();
        cacheHitsCounter.increment();
    }

    public void incrementCacheMisses() {
        cacheMisses.increment();
        cacheMissesCounter.increment();
    }

    public long getDbReads() {
        return dbReads.sum();
    }

    public long getDbWrites() {
        return dbWrites.sum();
    }

    public long getCacheHits() {
        return cacheHits.sum();
    }

    public long getCacheMisses() {
        return cacheMisses.sum();
    }

    public double getCacheHitRate() {
        long hits = cacheHits.sum();
        long misses = cacheMisses.sum();
        long total = hits + misses;
        if (total == 0) return 0.0;
        return (double) hits / total * 100.0;
    }

    public double getAvgLatencyMs() {
        List<Long> values = new ArrayList<>(latencyWindow);
        if (values.isEmpty()) return 0.0;
        long sum = 0;
        for (long v : values) {
            sum += v;
        }
        double avgNs = (double) sum / values.size();
        return Math.round((avgNs / 1_000_000.0) * 100.0) / 100.0; // Round to 2 decimals
    }

    public double getP95LatencyMs() {
        List<Long> values = new ArrayList<>(latencyWindow);
        if (values.isEmpty()) return 0.0;
        Collections.sort(values);
        int index = (int) Math.ceil(0.95 * values.size()) - 1;
        index = Math.max(0, Math.min(index, values.size() - 1));
        double p95Ns = values.get(index);
        return Math.round((p95Ns / 1_000_000.0) * 100.0) / 100.0; // Round to 2 decimals
    }
}
