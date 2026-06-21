# Search Typeahead System — Architecture Diagrams

This document contains all 4 Mermaid diagrams requested: System Architecture, Sequence Diagram (Read Flow), Batch Write Flow, and Consistent Hashing Flow.

---

## 1. System Architecture (Component Diagram)

```mermaid
graph TD
    subgraph Frontend
        React[React UI<br/>Debounced Search Box<br/>Keyboard Navigation<br/>HLD Dashboard]
    end

    subgraph Spring Boot Backend
        Controller[REST Controller<br/>/suggest /search /trending<br/>/metrics /cache/debug<br/>/ring/debug /health]

        subgraph Read Path
            SuggestionService[SuggestionService]
            CacheManager[DistributedCacheManager]
            HashRing[ConsistentHashRing<br/>SHA-256 + TreeMap<br/>100 Virtual Nodes per Physical]
            
            subgraph Distributed Cache
                Node1[Node-1<br/>Caffeine Cache]
                Node2[Node-2<br/>Caffeine Cache]
                Node3[Node-3<br/>Caffeine Cache]
                Node4[Node-4<br/>Caffeine Cache]
            end

            TrieService[TrieService<br/>ReentrantReadWriteLock]
            TrieNodes[In-Memory Trie<br/>100K+ Entries<br/>Pre-sorted Top 10 per Node]
        end

        subgraph Write Path
            SearchBuffer[SearchBuffer<br/>LinkedBlockingQueue<br/>Capacity 10000]
            WAL[Write-Ahead Log<br/>wal.log]
            BatchWriter[BatchWriterScheduler<br/>Every 5s or 100 items]
            JdbcBatch[JdbcTemplate<br/>batchUpdate<br/>ON CONFLICT DO UPDATE]
        end

        subgraph Analytics
            TrendingService[TrendingSearchService<br/>Exponential Decay<br/>Min-Heap Top-K]
            MetricsTracker[MetricsTracker<br/>LongAdder + Micrometer]
        end

        CacheWarmer[CacheWarmerScheduler<br/>Every 120s]
    end

    subgraph Database
        PostgreSQL[(PostgreSQL / H2 DB)]
    end

    React -->|HTTP| Controller
    Controller --> SuggestionService
    Controller --> SearchBuffer
    Controller --> TrendingService
    Controller --> MetricsTracker

    SuggestionService --> CacheManager
    CacheManager --> HashRing
    HashRing --> Node1
    HashRing --> Node2
    HashRing --> Node3
    HashRing --> Node4
    Node1 -->|Miss| TrieService
    Node2 -->|Miss| TrieService
    Node3 -->|Miss| TrieService
    Node4 -->|Miss| TrieService
    TrieService --> TrieNodes

    SearchBuffer --> WAL
    SearchBuffer --> BatchWriter
    BatchWriter --> JdbcBatch
    JdbcBatch --> PostgreSQL
    BatchWriter -->|Incremental Update| TrieService
    BatchWriter -->|Prefix Invalidation| CacheManager

    TrendingService --> PostgreSQL
    CacheWarmer --> TrieService
    CacheWarmer --> CacheManager
```

---

## 2. Sequence Diagram — Read Flow (GET /suggest)

```mermaid
sequenceDiagram
    participant User
    participant React as React UI
    participant Controller as SearchController
    participant Service as SuggestionService
    participant Metrics as MetricsTracker
    participant Cache as DistributedCacheManager
    participant Ring as ConsistentHashRing
    participant Node as CaffeineCacheNode
    participant Trie as TrieService

    User->>React: Types prefix "iph" (keystrokes)
    Note over React: Debounce 300ms
    React->>Controller: GET /api/suggest?q=iph
    Controller->>Service: getSuggestions("iph")
    Service->>Metrics: Start latency timer

    Service->>Cache: get("iph")
    Cache->>Ring: getNode("iph")
    Ring-->>Cache: "Node-2" (SHA-256 → TreeMap tailMap)
    Cache->>Node: get("iph") on Node-2

    alt Cache HIT
        Node-->>Cache: List of QuerySuggestion
        Cache-->>Service: Cached suggestions
        Service->>Metrics: incrementCacheHits()
    else Cache MISS
        Node-->>Cache: null
        Cache-->>Service: null
        Service->>Metrics: incrementCacheMisses()
        Service->>Trie: search("iph")
        Note over Trie: Acquire readLock()
        Trie-->>Service: top10 list (pre-sorted by totalCount)
        Service->>Cache: put("iph", suggestions)
    end

    Service->>Metrics: recordSuggestLatency(elapsed)
    Service-->>Controller: List of QuerySuggestion
    Controller-->>React: JSON Response
    React-->>User: Dropdown with suggestions
```

---

## 3. Batch Write Flow (POST /search)

```mermaid
sequenceDiagram
    participant User
    participant React as React UI
    participant Controller as SearchController
    participant Buffer as SearchBuffer
    participant WAL as wal.log
    participant Queue as LinkedBlockingQueue
    participant Scheduler as BatchWriterScheduler
    participant DB as PostgreSQL/H2
    participant Trie as TrieService
    participant Cache as DistributedCacheManager

    User->>React: Clicks Search or presses Enter
    React->>Controller: POST /api/search {"query":"iphone"}
    Controller->>Buffer: submit("iphone")

    Buffer->>WAL: Append "iphone" (durability first)
    Buffer->>Queue: offer("iphone") to LinkedBlockingQueue

    Note over Controller: Returns {"message":"Searched"} immediately

    Note over Scheduler: Trigger: every 5 seconds OR queue size >= 100

    Scheduler->>Queue: drainTo(list)
    Note over Scheduler: Aggregate duplicates:<br/>iphone → +3, java → +1

    Scheduler->>DB: Fetch existing records for queries
    Scheduler->>DB: JdbcTemplate.batchUpdate()<br/>UPDATE ... SET total_count += N<br/>INSERT ... ON CONFLICT DO UPDATE

    Scheduler->>Trie: insertOrUpdate("iphone", newTotalCount)
    Note over Trie: Acquire writeLock()<br/>Traverse prefix path<br/>Update top10 at each node

    Scheduler->>Cache: invalidatePrefixChain("iphone")
    Note over Cache: Invalidate: "i","ip","iph",<br/>"ipho","iphon","iphone"<br/>on their respective owner nodes

    Scheduler->>WAL: clearWal()
    Note over WAL: Truncated after successful DB commit

    Note over Scheduler: On crash before flush:<br/>WAL replayed on next startup
```

---

## 4. Consistent Hashing Flow

```mermaid
graph TD
    subgraph Hash Ring
        direction TB
        Ring[Circular Ring<br/>Key Space: 0 to 2^64]

        VN1A[Node-1 VN-0<br/>hash: 0x0A3F...]
        VN1B[Node-1 VN-1<br/>hash: 0x2B7C...]
        VN2A[Node-2 VN-0<br/>hash: 0x1D4E...]
        VN2B[Node-2 VN-1<br/>hash: 0x4A8B...]
        VN3A[Node-3 VN-0<br/>hash: 0x3C5D...]
        VN3B[Node-3 VN-1<br/>hash: 0x5F2A...]
        VN4A[Node-4 VN-0<br/>hash: 0x0E9F...]
        VN4B[Node-4 VN-1<br/>hash: 0x7B1C...]
    end

    Prefix["Prefix: iph"]
    SHA["SHA-256(iph) → 0x2E5A..."]
    Lookup["TreeMap.tailMap(0x2E5A...)"]
    Owner["First entry → Node-3 VN-0"]
    Result["Route to Node-3 CacheNode"]

    Prefix --> SHA
    SHA --> Lookup
    Lookup --> Owner
    Owner --> Result

    subgraph Minimal Key Movement
        Before["Before: 4 nodes (400 virtual nodes)"]
        After["After: Add Node-5 (+100 virtual nodes)"]
        Movement["Only ~20% of keys migrate to Node-5<br/>80% remain on their original nodes<br/>Keys ONLY move to the new node"]
    end

    subgraph Ring Debug API Response
        DebugJSON["GET /api/ring/debug<br/>{<br/>  virtualNodesPerPhysical: 100,<br/>  distribution: {<br/>    Node-1: 24.8%,<br/>    Node-2: 25.1%,<br/>    Node-3: 25.4%,<br/>    Node-4: 24.7%<br/>  },<br/>  totalKeys: 34<br/>}"]
    end

    style VN1A fill:#ef4444
    style VN1B fill:#ef4444
    style VN2A fill:#3b82f6
    style VN2B fill:#3b82f6
    style VN3A fill:#10b981
    style VN3B fill:#10b981
    style VN4A fill:#f59e0b
    style VN4B fill:#f59e0b
```
