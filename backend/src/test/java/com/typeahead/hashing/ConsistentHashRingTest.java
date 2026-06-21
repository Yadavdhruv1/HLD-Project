package com.typeahead.hashing;

import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

public class ConsistentHashRingTest {

    @Test
    public void testNodeMapping() {
        ConsistentHashRing ring = new ConsistentHashRing();
        String node1 = ring.getNode("iphone");
        String node2 = ring.getNode("java");
        
        assertNotNull(node1);
        assertNotNull(node2);
        
        // Consistent routing: routing same key twice gives same node
        assertEquals(node1, ring.getNode("iphone"));
        assertEquals(node2, ring.getNode("java"));
    }

    @Test
    public void testAddAndRemoveNode() {
        ConsistentHashRing ring = new ConsistentHashRing();
        int initialSize = ring.getRingSize();
        assertEquals(400, initialSize); // 4 nodes * 100 virtual nodes

        ring.addNode("Node-5");
        assertEquals(500, ring.getRingSize());

        ring.removeNode("Node-5");
        assertEquals(400, ring.getRingSize());
    }

    @Test
    public void testMinimalKeyMovement() {
        ConsistentHashRing ring = new ConsistentHashRing();
        
        // 1. Map 1000 keys to initial 4 nodes
        Map<String, String> initialMappings = new HashMap<>();
        for (int i = 0; i < 1000; i++) {
            String key = "test-query-" + i;
            initialMappings.put(key, ring.getNode(key));
        }

        // 2. Add Node-5 to the ring
        ring.addNode("Node-5");

        // 3. Re-map the same 1000 keys and measure movement
        int movedKeys = 0;
        int movedToNode5 = 0;
        
        for (int i = 0; i < 1000; i++) {
            String key = "test-query-" + i;
            String originalNode = initialMappings.get(key);
            String newNode = ring.getNode(key);
            
            if (!originalNode.equals(newNode)) {
                movedKeys++;
                if (newNode.equals("Node-5")) {
                    movedToNode5++;
                }
            }
        }

        // Mathematically, only about 1/5 (20%) of keys should move, and they should ONLY move to Node-5.
        // The other 80% should remain on Node-1, Node-2, Node-3, Node-4.
        double movementPercent = (movedKeys * 100.0) / 1000;
        
        System.out.println("Total keys moved: " + movedKeys + " (" + movementPercent + "%)");
        System.out.println("Keys moved to the new Node-5: " + movedToNode5);

        // Verify that keys only moved to Node-5 (since no other node was added/removed, no keys should move between Node-1..4)
        assertEquals(movedKeys, movedToNode5, "Keys should only move to the newly added Node-5");
        
        // Verify key movement is close to theoretical 20% (allow buffer for statistical variance, e.g. < 30%)
        assertTrue(movementPercent < 30.0, "Key movement should be minimal (< 30%)");
    }
}
