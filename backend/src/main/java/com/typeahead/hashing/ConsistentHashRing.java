package com.typeahead.hashing;

import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Component
public class ConsistentHashRing {

    private final TreeMap<Long, String> ring = new TreeMap<>();
    private final int virtualNodesPerPhysical = 100;
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

    public ConsistentHashRing() {
        // Initialize with default 4 nodes as per requirements
        addNode("Node-1");
        addNode("Node-2");
        addNode("Node-3");
        addNode("Node-4");
    }

    public int getVirtualNodesPerPhysical() {
        return virtualNodesPerPhysical;
    }

    public void addNode(String node) {
        rwLock.writeLock().lock();
        try {
            for (int i = 0; i < virtualNodesPerPhysical; i++) {
                long hash = hash(node + "-vn-" + i);
                ring.put(hash, node);
            }
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    public void removeNode(String node) {
        rwLock.writeLock().lock();
        try {
            for (int i = 0; i < virtualNodesPerPhysical; i++) {
                long hash = hash(node + "-vn-" + i);
                ring.remove(hash);
            }
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    public String getNode(String key) {
        rwLock.readLock().lock();
        try {
            if (ring.isEmpty()) {
                return null;
            }
            long hash = hash(key);
            if (!ring.containsKey(hash)) {
                SortedMap<Long, String> tailMap = ring.tailMap(hash);
                hash = tailMap.isEmpty() ? ring.firstKey() : tailMap.firstKey();
            }
            return ring.get(hash);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public long hash(String key) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(key.getBytes(StandardCharsets.UTF_8));
            long result = 0;
            for (int i = 0; i < 8; i++) {
                result = (result << 8) | (bytes[i] & 0xffL);
            }
            return result;
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not supported", e);
        }
    }

    public Map<String, Double> getDistribution() {
        rwLock.readLock().lock();
        try {
            if (ring.isEmpty()) {
                return Collections.emptyMap();
            }
            Map<String, Integer> counts = new HashMap<>();
            int samples = 10000;
            // Sample keys to measure the statistical load distribution of the ring
            for (int i = 0; i < samples; i++) {
                String key = "sample-query-key-prefix-" + i;
                String node = getNode(key);
                if (node != null) {
                    counts.put(node, counts.getOrDefault(node, 0) + 1);
                }
            }
            Map<String, Double> distribution = new LinkedHashMap<>();
            // Order nodes consistently
            List<String> nodes = Arrays.asList("Node-1", "Node-2", "Node-3", "Node-4");
            for (String node : nodes) {
                int count = counts.getOrDefault(node, 0);
                double pct = (count * 100.0) / samples;
                distribution.put(node, Math.round(pct * 10.0) / 10.0); // round to 1 decimal place
            }
            return distribution;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public int getRingSize() {
        rwLock.readLock().lock();
        try {
            return ring.size();
        } finally {
            rwLock.readLock().unlock();
        }
    }
}
