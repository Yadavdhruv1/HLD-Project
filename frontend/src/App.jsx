import React, { useState, useEffect, useRef } from 'react';

const API_BASE = '/api';

function App() {
  const [searchQuery, setSearchQuery] = useState('');
  const [suggestions, setSuggestions] = useState([]);
  const [trending, setTrending] = useState([]);
  const [rankingMode, setRankingMode] = useState('basic');
  const [metrics, setMetrics] = useState({
    avgLatency: 0,
    p95Latency: 0,
    cacheHitRate: 0,
    dbReads: 0,
    dbWrites: 0,
    queueSize: 0,
    cachedKeys: 0
  });
  const [ringDebug, setRingDebug] = useState({
    virtualNodesPerPhysical: 100,
    distribution: {
      'Node-1': 25.0,
      'Node-2': 25.0,
      'Node-3': 25.0,
      'Node-4': 25.0
    },
    totalKeys: 0
  });
  const [health, setHealth] = useState({
    database: 'DOWN',
    cache: 'DOWN',
    trieLoaded: false,
    walEnabled: false
  });

  const [activeSuggestion, setActiveSuggestion] = useState(-1);
  const [routingInfo, setRoutingInfo] = useState(null);
  const [logs, setLogs] = useState([]);
  const [loadTestInterval, setLoadTestInterval] = useState(null);
  const [toastMessage, setToastMessage] = useState(null);

  // References for keyboard navigation and scroll
  const dropdownRef = useRef(null);
  const logEndRef = useRef(null);

  // Helper to add flow logs
  const addLog = (tag, message) => {
    const time = new Date().toLocaleTimeString();
    setLogs((prev) => [
      { time, tag, message },
      ...prev.slice(0, 49) // Keep last 50 logs
    ]);
  };

  // Fetch initial data & start polling
  useEffect(() => {
    fetchHealth();
    fetchTrending();
    fetchMetrics();
    fetchRingDebug();

    const interval = setInterval(() => {
      fetchMetrics();
      fetchTrending();
      fetchHealth();
      fetchRingDebug();
    }, 2000);

    addLog('SYSTEM', 'HLD Dashboard Initialized. Defaulting to local Spring Boot backend.');

    return () => {
      clearInterval(interval);
      if (loadTestInterval) clearInterval(loadTestInterval);
    };
  }, []);

  // Fetch functions
  const fetchHealth = async () => {
    try {
      const res = await fetch(`${API_BASE}/health`);
      if (res.ok) {
        const data = await res.json();
        setHealth(data);
      }
    } catch (e) {
      setHealth({ database: 'DOWN', cache: 'DOWN', trieLoaded: false, walEnabled: false });
    }
  };

  const fetchTrending = async () => {
    try {
      const res = await fetch(`${API_BASE}/trending`);
      if (res.ok) {
        const data = await res.json();
        setTrending(data);
      }
    } catch (e) {}
  };

  const fetchMetrics = async () => {
    try {
      const res = await fetch(`${API_BASE}/metrics`);
      if (res.ok) {
        const data = await res.json();
        setMetrics(data);
      }
    } catch (e) {}
  };

  const fetchRingDebug = async () => {
    try {
      const res = await fetch(`${API_BASE}/ring/debug`);
      if (res.ok) {
        const data = await res.json();
        setRingDebug(data);
      }
    } catch (e) {}
  };

  // Debouncing effect for suggestions
  useEffect(() => {
    if (!searchQuery.trim()) {
      setSuggestions([]);
      setRoutingInfo(null);
      return;
    }

    const timer = setTimeout(() => {
      getSuggestionsAndRouting(searchQuery);
    }, 300);

    return () => clearTimeout(timer);
  }, [searchQuery, rankingMode]);

  const getSuggestionsAndRouting = async (prefix) => {
    try {
      // 1. Get suggestions
      const start = performance.now();
      const res = await fetch(`${API_BASE}/suggest?q=${encodeURIComponent(prefix)}&ranking=${rankingMode}`);
      const elapsed = (performance.now() - start).toFixed(1);

      if (res.ok) {
        const data = await res.json();
        setSuggestions(data);

        // 2. Get routing debug details
        const debugRes = await fetch(`${API_BASE}/cache/debug?prefix=${encodeURIComponent(prefix)}`);
        if (debugRes.ok) {
          const debugData = await debugRes.json();
          setRoutingInfo(debugData);

          const hitTag = debugData.hit ? 'HIT' : 'MISS';
          addLog(
            'READ',
            `Prefix "${prefix}" -> SHA-256 Hash [${debugData.hash}] routed to ${debugData.cacheNode} (Cache ${hitTag}) in ${elapsed}ms. Returned ${data.length} matches.`
          );
        }
      }
    } catch (e) {
      addLog('SYSTEM', `Failed to fetch suggestions for prefix: "${prefix}"`);
    }
  };

  // Search Submit function
  const handleSearchSubmit = async (queryStr) => {
    const targetQuery = queryStr || searchQuery;
    if (!targetQuery.trim()) return;

    try {
      addLog('WRITE', `Submitting search: "${targetQuery}"`);
      const res = await fetch(`${API_BASE}/search`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ query: targetQuery })
      });

      if (res.ok) {
        setToastMessage(`Search event queued for "${targetQuery}"`);
        setTimeout(() => setToastMessage(null), 3000);
        addLog('WRITE', `[WAL + QUEUE] Query enqueued. Pending batch flusher (5s/100 size limit)`);
      } else {
        const errData = await res.json();
        addLog('WRITE', `Search submission rejected: ${errData.error}`);
      }
    } catch (e) {
      addLog('SYSTEM', 'Search request failed.');
    }

    // Reset autocomplete dropdown
    setSuggestions([]);
    setSearchQuery('');
    setActiveSuggestion(-1);
  };

  // Keyboard navigation inside dropdown suggestions
  const handleKeyDown = (e) => {
    if (suggestions.length === 0) return;

    if (e.key === 'ArrowDown') {
      e.preventDefault();
      setActiveSuggestion((prev) => (prev < suggestions.length - 1 ? prev + 1 : 0));
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      setActiveSuggestion((prev) => (prev > 0 ? prev - 1 : suggestions.length - 1));
    } else if (e.key === 'Enter') {
      e.preventDefault();
      if (activeSuggestion >= 0 && activeSuggestion < suggestions.length) {
        handleSearchSubmit(suggestions[activeSuggestion].query);
      } else {
        handleSearchSubmit();
      }
    } else if (e.key === 'Escape') {
      setSuggestions([]);
      setActiveSuggestion(-1);
    }
  };

  // Simulated Load Testing Client
  const toggleLoadTest = () => {
    if (loadTestInterval) {
      clearInterval(loadTestInterval);
      setLoadTestInterval(null);
      addLog('SYSTEM', 'Stopped Load Test Simulator.');
    } else {
      addLog('SYSTEM', 'Started Load Test Simulator (Generating concurrent Suggest/Search requests)...');

      const prefixes = ['i', 'ip', 'iph', 'jav', 'spr', 'rea', 'con', 'pos', 'red', 'cha', 'ope', 'nik', 'bit', 'fl', 'ho'];
      const queries = [
        'iphone', 'iphone 15', 'iphone charger', 'java tutorial', 'spring boot', 'react js',
        'consistent hashing', 'postgresql', 'redis cache', 'chatgpt ai', 'openai models',
        'nike shoes', 'adidas sneakers', 'rolex watches', 'sony camera', 'mechanical keyboard',
        'gaming monitor', 'coffee machine', 'tesla roadster', 'bitcoin trading', 'hotel bookings'
      ];

      const intervalId = setInterval(() => {
        // 90% read requests, 10% search submission writes
        if (Math.random() < 0.90) {
          const randPrefix = prefixes[Math.floor(Math.random() * prefixes.length)];
          // Append random chars occasionally for variations
          const fullPrefix = Math.random() < 0.3 ? randPrefix + String.fromCharCode(97 + Math.floor(Math.random() * 26)) : randPrefix;
          fetchSuggestionsSilently(fullPrefix);
        } else {
          const randQuery = queries[Math.floor(Math.random() * queries.length)];
          submitSearchSilently(randQuery);
        }
      }, 50); // 20 requests per second (scale down locally for smooth UI rendering)

      setLoadTestInterval(intervalId);
    }
  };

  const fetchSuggestionsSilently = async (prefix) => {
    try {
      const res = await fetch(`${API_BASE}/suggest?q=${encodeURIComponent(prefix)}`);
      if (res.ok) {
        const debugRes = await fetch(`${API_BASE}/cache/debug?prefix=${encodeURIComponent(prefix)}`);
        if (debugRes.ok) {
          const debugData = await debugRes.json();
          // Log occasionally to prevent spam
          if (Math.random() < 0.15) {
            const hitTag = debugData.hit ? 'HIT' : 'MISS';
            addLog('READ', `[LOADTEST] Prefix "${prefix}" routed to ${debugData.cacheNode} (${hitTag})`);
          }
        }
      }
    } catch (e) {}
  };

  const submitSearchSilently = async (queryStr) => {
    try {
      const res = await fetch(`${API_BASE}/search`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ query: queryStr })
      });
      if (res.ok && Math.random() < 0.25) {
        addLog('WRITE', `[LOADTEST] Submitted write query: "${queryStr}"`);
      }
    } catch (e) {}
  };

  return (
    <div className="dashboard-container">
      {/* Search Submission success toast banner */}
      {toastMessage && (
        <div className="search-success-toast">
          <span>✔️</span> {toastMessage}
        </div>
      )}

      {/* Main Header */}
      <header className="header">
        <div className="logo-section">
          <h1>Search Autocomplete <span className="logo-badge">HLD Engine</span></h1>
          <p>Distributed autocomplete service styled after Google Search with consistent hashing & WAL</p>
        </div>
        <div className="health-panel">
          <div className="health-badge">
            Database: <span className={`status-dot ${health.database === 'UP' ? 'up' : 'down'}`}></span> {health.database}
          </div>
          <div className="health-badge">
            Distributed Cache: <span className={`status-dot ${health.cache === 'UP' ? 'up' : 'down'}`}></span> {health.cache}
          </div>
          <div className="health-badge">
            Trie Loaded: <span className={`status-dot ${health.trieLoaded ? 'up' : 'down'}`}></span> {health.trieLoaded ? 'TRUE' : 'FALSE'}
          </div>
          <div className="health-badge">
            WAL Active: <span className={`status-dot ${health.walEnabled ? 'up' : 'down'}`}></span> {health.walEnabled ? 'TRUE' : 'FALSE'}
          </div>
        </div>
      </header>

      {/* Operational Metrics Panel */}
      <div className="metrics-grid">
        <div className="metric-card">
          <div className="metric-value">{metrics.avgLatency} ms</div>
          <div className="metric-label">Avg Read Latency</div>
        </div>
        <div className="metric-card">
          <div className="metric-value">{metrics.p95Latency} ms</div>
          <div className="metric-label">P95 Read Latency</div>
        </div>
        <div className="metric-card">
          <div className="metric-value">{metrics.cacheHitRate}%</div>
          <div className="metric-label">Cache Hit Ratio</div>
        </div>
        <div className="metric-card">
          <div className="metric-value">{metrics.dbReads}</div>
          <div className="metric-label">DB Reads</div>
        </div>
        <div className="metric-card">
          <div className="metric-value">{metrics.dbWrites}</div>
          <div className="metric-label">DB Writes (Batch)</div>
        </div>
      </div>

      <div className="dashboard-grid">
        {/* Left Column: Search Box & Ring Visualizer */}
        <div>
          {/* Main Google Search Card */}
          <div className="card" style={{ minHeight: '340px' }}>
            <div className="search-center-wrapper">
              <div className="ranking-toggle-container">
                <span className="toggle-label">Suggestions Mode:</span>
                <button
                  className={`toggle-btn ${rankingMode === 'basic' ? 'active' : ''}`}
                  onClick={() => setRankingMode('basic')}
                >
                  Popularity (Basic)
                </button>
                <button
                  className={`toggle-btn ${rankingMode === 'recency' ? 'active' : ''}`}
                  onClick={() => setRankingMode('recency')}
                >
                  Recency-Aware (Enhanced)
                </button>
              </div>

              <div className="search-box-container">
                <div className="search-input-wrapper">
                  <span className="search-icon">🔍</span>
                  <input
                    type="text"
                    className="search-input"
                    placeholder="Search queries (e.g. iphone, java)..."
                    value={searchQuery}
                    onChange={(e) => setSearchQuery(e.target.value)}
                    onKeyDown={handleKeyDown}
                  />
                  {searchQuery && (
                    <button className="clear-button" onClick={() => setSearchQuery('')}>
                      ✖
                    </button>
                  )}
                  <button className="search-submit-btn" onClick={() => handleSearchSubmit()}>
                    Search
                  </button>
                </div>

                {/* Autocomplete Dropdown List */}
                {suggestions.length > 0 && (
                  <div className="suggestions-dropdown" ref={dropdownRef}>
                    {suggestions.map((suggestion, idx) => (
                      <div
                        key={idx}
                        className={`suggestion-item ${activeSuggestion === idx ? 'active' : ''}`}
                        onClick={() => handleSearchSubmit(suggestion.query)}
                        onMouseEnter={() => setActiveSuggestion(idx)}
                      >
                        <span className="suggestion-query">{suggestion.query}</span>
                        <div className="suggestion-meta">
                          <span>{suggestion.count.toLocaleString()} total</span>
                          {suggestion.score !== undefined && suggestion.score !== null && (
                            <span style={{ color: '#60a5fa' }}>Score: {suggestion.score.toFixed(3)}</span>
                          )}
                          {suggestion.recentCount !== undefined && suggestion.recentCount !== null && suggestion.recentCount > 0 && (
                            <span style={{ color: '#34d399' }}>Recent: {suggestion.recentCount.toFixed(1)}</span>
                          )}
                        </div>
                      </div>
                    ))}
                    <div className="dropdown-footer">
                      Use <span className="kbd-shortcut">↓</span> <span className="kbd-shortcut">↑</span> to navigate, <span className="kbd-shortcut">Enter</span> to select
                    </div>
                  </div>
                )}
              </div>

              {/* Cache Routing Debug Peeker */}
              {routingInfo && (
                <div className="routing-peeker">
                  <div className="routing-stat">
                    <span>Hashing Ring:</span>
                    <span className="node-badge">{routingInfo.cacheNode}</span>
                  </div>
                  <div className="routing-stat">
                    <span>SHA-256 Hash:</span>
                    <span style={{ fontFamily: 'monospace', color: '#f1f5f9' }}>{routingInfo.hash}</span>
                  </div>
                  <div className="routing-stat">
                    <span>Status:</span>
                    <span className={routingInfo.hit ? 'hit-badge' : 'miss-badge'}>
                      {routingInfo.hit ? 'CACHE HIT' : 'CACHE MISS'}
                    </span>
                  </div>
                </div>
              )}
            </div>
          </div>

          {/* Consistent Hashing Ring Coverage Visualizer */}
          <div className="card">
            <h3 className="card-title">🌐 Consistent Hashing Ring Coverage</h3>
            <div className="ring-viz-container">
              <p style={{ color: '#94a3b8', fontSize: '0.9rem', margin: '0 0 1rem 0' }}>
                Key space ($0$ to $2^{64}-1$) divided among 4 physical cache nodes using {ringDebug.virtualNodesPerPhysical} virtual nodes each.
              </p>
              
              <div className="ring-bar-wrapper">
                {Object.entries(ringDebug.distribution).map(([nodeName, pct], idx) => {
                  let colorClass = 'node-1-color';
                  if (nodeName === 'Node-2') colorClass = 'node-2-color';
                  if (nodeName === 'Node-3') colorClass = 'node-3-color';
                  if (nodeName === 'Node-4') colorClass = 'node-4-color';
                  return (
                    <div
                      key={nodeName}
                      className={`ring-segment ${colorClass}`}
                      style={{ width: `${pct}%` }}
                    >
                      {pct > 5 ? `${pct}%` : ''}
                    </div>
                  );
                })}
              </div>

              <div className="ring-legend">
                {Object.entries(ringDebug.distribution).map(([nodeName, pct]) => {
                  let colorClass = 'node-1-color';
                  if (nodeName === 'Node-2') colorClass = 'node-2-color';
                  if (nodeName === 'Node-3') colorClass = 'node-3-color';
                  if (nodeName === 'Node-4') colorClass = 'node-4-color';
                  return (
                    <div key={nodeName} className="legend-item">
                      <div className="legend-header">
                        <span className={`legend-dot ${colorClass}`}></span>
                        {nodeName}
                      </div>
                      <div className="legend-desc">{pct}% Coverage</div>
                    </div>
                  );
                })}
              </div>

              <div style={{ marginTop: '1.25rem', paddingTop: '1rem', borderTop: '1px solid #1e293b', display: 'flex', justifyContent: 'space-between', fontSize: '0.85rem' }}>
                <span style={{ color: '#64748b' }}>Buffer Queue Backlog: <strong style={{ color: '#cbd5e1' }}>{metrics.queueSize} items</strong></span>
                <span style={{ color: '#64748b' }}>Total Cached Prefixes: <strong style={{ color: '#cbd5e1' }}>{metrics.cachedKeys} keys</strong></span>
              </div>
            </div>
          </div>
        </div>

        {/* Right Column: Trending Searches & Logs */}
        <div>
          {/* Trending Searches Sidepanel */}
          <div className="card">
            <h3 className="card-title">📈 Top Trending Searches</h3>
            <p style={{ color: '#64748b', fontSize: '0.8rem', margin: '-0.5rem 0 1rem 0' }}>
              Ranked dynamically with recency exponential decay: score = 0.7 × total + 0.3 × recent • e^(-λt)
            </p>
            <div className="trending-list">
              {trending.map((item, idx) => (
                <div key={idx} className="trending-item">
                  <span className="trending-rank">{idx + 1}</span>
                  <span className="trending-query" onClick={() => setSearchQuery(item.query)}>
                    {item.query}
                  </span>
                  <span className="trending-score-badge">{item.score}</span>
                </div>
              ))}
              {trending.length === 0 && (
                <div style={{ textAlign: 'center', color: '#475569', padding: '1rem' }}>
                  No search metrics recorded yet.
                </div>
              )}
            </div>
          </div>

          {/* System Control Panel & Load Simulator */}
          <div className="card">
            <h3 className="card-title">⚙️ HLD Simulator Controls</h3>
            <div className="loadtest-panel">
              <button
                className={`loadtest-btn ${loadTestInterval ? 'running' : ''}`}
                onClick={toggleLoadTest}
              >
                {loadTestInterval ? '⏹️ Stop Load Simulator' : '⚡ Start Load Simulator (20 req/s)'}
              </button>
              <div style={{ fontSize: '0.8rem', color: '#64748b', flex: 1 }}>
                Simulates concurrent suggestions & search submissions to demonstrate batch updates and cache invalidation.
              </div>
            </div>
          </div>

          {/* Live System Logging Console */}
          <div className="card">
            <h3 className="card-title">🖥️ System Flow Console Log</h3>
            <div className="flow-log">
              {logs.map((log, idx) => (
                <div key={idx} className="flow-log-line">
                  <span className="flow-log-time">[{log.time}]</span>
                  <span className={`flow-log-tag ${log.tag.toLowerCase()}`}>{log.tag}</span>
                  <span>{log.message}</span>
                </div>
              ))}
              {logs.length === 0 && (
                <div style={{ color: '#475569', textAlign: 'center', padding: '1rem 0' }}>
                  No system logs yet. Start typing to see log flow...
                </div>
              )}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}

export default App;
