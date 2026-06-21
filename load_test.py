import asyncio
import aiohttp
import random
import time
import sys

# Configurations
API_URL = "http://localhost:8080/api"
SUGGEST_URL = f"{API_URL}/suggest"
SEARCH_URL = f"{API_URL}/search"
METRICS_URL = f"{API_URL}/metrics"

PREFIXES = ["i", "ip", "iph", "jav", "spr", "rea", "con", "pos", "red", "cha", "ope", "nik", "bit", "fl", "ho", "a", "b", "c", "t"]
QUERIES = [
    "iphone", "iphone 15", "iphone charger", "java tutorial", "spring boot", "react js",
    "consistent hashing", "postgresql", "redis cache", "chatgpt ai", "openai models",
    "nike shoes", "adidas sneakers", "rolex watches", "sony camera", "mechanical keyboard",
    "gaming monitor", "coffee machine", "tesla roadster", "bitcoin trading", "hotel bookings"
]

class Stats:
    def __init__(self):
        self.total_requests = 0
        self.suggest_requests = 0
        self.search_requests = 0
        self.successes = 0
        self.failures = 0
        self.latencies = []

async def send_suggest(session, stats):
    prefix = random.choice(PREFIXES)
    if random.random() < 0.3:
        prefix += random.choice("abcdefghijklmnopqrstuvwxyz")
    
    start = time.perf_counter()
    try:
        async with session.get(SUGGEST_URL, params={"q": prefix}, timeout=2) as response:
            latency = (time.perf_counter() - start) * 1000.0
            stats.total_requests += 1
            stats.suggest_requests += 1
            if response.status == 200:
                stats.successes += 1
                stats.latencies.append(latency)
            else:
                stats.failures += 1
    except Exception:
        stats.failures += 1

async def send_search(session, stats):
    query = random.choice(QUERIES)
    start = time.perf_counter()
    try:
        async with session.post(SEARCH_URL, json={"query": query}, timeout=2) as response:
            latency = (time.perf_counter() - start) * 1000.0
            stats.total_requests += 1
            stats.search_requests += 1
            if response.status == 200 or response.status == 201:
                stats.successes += 1
                stats.latencies.append(latency)
            else:
                stats.failures += 1
    except Exception:
        stats.failures += 1

async def worker(session, stats, duration, rate_per_worker):
    start_time = time.time()
    interval = 1.0 / rate_per_worker
    
    while time.time() - start_time < duration:
        loop_start = time.perf_counter()
        
        # 90% reads (suggest), 10% writes (search submit)
        if random.random() < 0.90:
            await send_suggest(session, stats)
        else:
            await send_search(session, stats)
            
        elapsed = time.perf_counter() - loop_start
        sleep_time = interval - elapsed
        if sleep_time > 0:
            await asyncio.sleep(sleep_time)

async def main():
    duration = 10  # Duration in seconds
    concurrency = 25  # Parallel workers
    rate_per_worker = 40  # Requests per second per worker (Total target = 25 * 40 = 1000 req/sec)
    
    print("=" * 60)
    print(f"STARTING SEARCH TYPEAHEAD LOAD TESTING SYSTEM")
    print(f"Targeting ~{concurrency * rate_per_worker} req/sec for {duration} seconds...")
    print(f"Using {concurrency} parallel async workers.")
    print("=" * 60)
    
    stats = Stats()
    start_time = time.time()
    
    async with aiohttp.ClientSession() as session:
        # Pre-check endpoint health
        try:
            async with session.get(METRICS_URL) as r:
                if r.status != 200:
                    print("Error: Backend doesn't seem to be responding on port 8080.")
                    sys.exit(1)
        except Exception as e:
            print(f"Error connecting to backend: {e}")
            print("Make sure your Spring Boot application is running on http://localhost:8080")
            sys.exit(1)
            
        tasks = []
        for _ in range(concurrency):
            tasks.append(worker(session, stats, duration, rate_per_worker))
            
        await asyncio.gather(*tasks)
        
    end_time = time.time()
    total_time = end_time - start_time
    
    avg_latency = sum(stats.latencies) / len(stats.latencies) if stats.latencies else 0
    p95_latency = 0
    if stats.latencies:
        sorted_latencies = sorted(stats.latencies)
        p95_idx = int(len(sorted_latencies) * 0.95)
        p95_latency = sorted_latencies[min(p95_idx, len(sorted_latencies) - 1)]
        
    req_per_sec = stats.total_requests / total_time
    
    print("\n" + "=" * 60)
    print("LOAD TEST RESULTS SUMMARY")
    print("=" * 60)
    print(f"Total Test Duration      : {total_time:.2f} seconds")
    print(f"Total Requests Executed  : {stats.total_requests}")
    print(f"  - Suggestions (GET)    : {stats.suggest_requests}")
    print(f"  - Searches (POST)       : {stats.search_requests}")
    print(f"Successful Requests      : {stats.successes} ({stats.successes / stats.total_requests * 100:.1f}%)")
    print(f"Failed Requests          : {stats.failures}")
    print(f"Throughput (RPS)         : {req_per_sec:.1f} req/sec")
    print(f"Average Client Latency   : {avg_latency:.2f} ms")
    print(f"P95 Client Latency       : {p95_latency:.2f} ms")
    print("=" * 60)
    
    # Try fetching operational stats from metrics endpoint
    try:
        async with aiohttp.ClientSession() as session:
            async with session.get(METRICS_URL) as r:
                if r.status == 200:
                    backend_metrics = await r.json()
                    print("\nBACKEND REPORTED METRICS:")
                    print("-" * 60)
                    print(f"Backend Average Latency  : {backend_metrics.get('avgLatency')} ms")
                    print(f"Backend P95 Latency      : {backend_metrics.get('p95Latency')} ms")
                    print(f"Cache Hit Ratio          : {backend_metrics.get('cacheHitRate')}%")
                    print(f"Total DB Reads           : {backend_metrics.get('dbReads')}")
                    print(f"Total DB Writes (Batch)  : {backend_metrics.get('dbWrites')}")
                    print(f"Queue Backlog Size       : {backend_metrics.get('queueSize')}")
                    print(f"Cached Key Prefixes      : {backend_metrics.get('cachedKeys')}")
                    print("=" * 60)
    except Exception:
        pass

if __name__ == "__main__":
    # Check if aiohttp is installed, otherwise advise
    try:
        import aiohttp
    except ImportError:
        print("Python module 'aiohttp' is required for the load test script.")
        print("Install it with: pip install aiohttp")
        sys.exit(1)
        
    asyncio.run(main())
