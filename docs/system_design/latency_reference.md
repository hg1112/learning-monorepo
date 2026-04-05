# Latency Reference — Backend & ML Infrastructure

Numbers are for a **single node, local hardware (NVMe SSD, 16–32GB RAM)** at p50 and p99
under moderate load. Real-world numbers vary with hardware, data size, and config.

---

## Memory Hierarchy — Mental Model

| Level | Latency | Notes |
|-------|---------|-------|
| L1 cache | ~0.5 ns | ~4 cycles |
| L2 cache | ~7 ns | |
| L3 cache | ~20 ns | Shared across cores |
| RAM (DRAM read) | ~100 ns | DDR4/5 |
| NVMe SSD (random read) | ~20–100 µs | Gen4 PCIe |
| SATA SSD (random read) | ~100–500 µs | |
| HDD (random read) | ~5–10 ms | Seek time |
| Loopback (127.0.0.1) | ~0.01–0.05 ms | kernel bypass possible |
| Same datacenter LAN | ~0.2–1 ms | 10GbE |
| Cross-datacenter (same region) | ~5–15 ms | |
| Cross-continent | ~50–150 ms | |

**Rule of thumb:** `RAM < SSD < disk < loopback < LAN < cross-DC`

---

## Databases

### Redis
Operations are in-memory, single-threaded command processing.

| Operation | p50 | p99 | Notes |
|-----------|-----|-----|-------|
| GET / SET | 0.1 ms | 0.3 ms | Simple key-value |
| MGET / MSET (10 keys) | 0.15 ms | 0.4 ms | Multi-key |
| LPUSH / LPOP | 0.1 ms | 0.3 ms | List ops |
| ZADD / ZSCORE | 0.15 ms | 0.4 ms | Sorted set (O(log N)) |
| ZRANGE (10 results) | 0.2 ms | 0.5 ms | Range scan |
| HSET / HGET | 0.1 ms | 0.3 ms | Hash field |
| SADD / SMEMBERS | 0.1 ms | 0.5 ms | Set; SMEMBERS scales with set size |
| PIPELINE (100 cmds) | 0.5 ms | 2 ms | Batched over one TCP round-trip |
| EVAL (Lua script, simple) | 0.2 ms | 0.8 ms | Atomic server-side logic |
| PUBSUB publish | 0.1 ms | 0.3 ms | Per subscriber fan-out adds latency |

**Max throughput:** 100K–500K ops/s (single-threaded); pipelining pushes toward 1M ops/s.

---

### PostgreSQL
Assumes B-tree indexes, warm buffer pool (shared_buffers), default config.

| Operation | p50 | p99 | Notes |
|-----------|-----|-----|-------|
| Point read (PK lookup) | 1 ms | 5 ms | Single-row, indexed |
| Point read (secondary index) | 1.5 ms | 8 ms | Heap fetch after index scan |
| Sequential scan (1K rows) | 2 ms | 10 ms | Small table, warm cache |
| Sequential scan (1M rows) | 50 ms | 200 ms+ | Full-table; avoid without LIMIT |
| INSERT (single row) | 2 ms | 8 ms | WAL flush (fsync) included |
| UPDATE (by PK) | 2 ms | 10 ms | MVCC write + WAL |
| DELETE (by PK) | 2 ms | 8 ms | Marks tuple dead; vacuumed later |
| Simple JOIN (2 tables, indexed) | 3 ms | 15 ms | Nested loop or hash join |
| BEGIN … COMMIT (5 ops) | 5 ms | 20 ms | Round-trip per statement |
| COPY (bulk insert, 10K rows) | 20 ms | 80 ms | Much faster than individual INSERTs |
| EXPLAIN (complex query) | 0.1 ms | 1 ms | Plan only, no execution |

**Max throughput:** 5K–20K simple queries/s per connection pool of ~50; connection overhead is key bottleneck.

---

### Cassandra
Optimized for write-heavy workloads; reads need partition-key access for best latency.

| Operation | p50 | p99 | Notes |
|-----------|-----|-----|-------|
| INSERT (single row) | 0.5 ms | 3 ms | Append-only, no read-before-write |
| SELECT by partition key | 1 ms | 5 ms | Direct partition lookup (RF=1) |
| SELECT by partition + cluster | 1 ms | 5 ms | B-tree within partition |
| SELECT with secondary index | 5 ms | 30 ms | Cross-node scatter; avoid in hot paths |
| ALLOW FILTERING scan | 50 ms – 5 s | — | Full-cluster scan; never in production |
| BATCH (10 inserts, same partition) | 2 ms | 8 ms | Same partition = single node |
| BATCH (multi-partition) | 10 ms | 50 ms | Coordinator round-trips each partition |
| Lightweight transaction (CAS) | 10 ms | 50 ms | Paxos overhead |
| TTL expiry (background) | — | — | Async; no read latency impact |

**Max throughput:** 20K–100K writes/s per node; reads lower (~10K–50K) due to compaction.

---

### MongoDB
WiredTiger storage engine; document model.

| Operation | p50 | p99 | Notes |
|-----------|-----|-----|-------|
| findOne (by _id) | 1 ms | 5 ms | Direct ObjectId lookup |
| findOne (indexed field) | 1.5 ms | 8 ms | B-tree traversal |
| find (10 docs, indexed) | 2 ms | 10 ms | |
| insertOne | 2 ms | 8 ms | Journal flush included |
| updateOne (by _id) | 2 ms | 10 ms | In-place if same size |
| deleteOne | 2 ms | 8 ms | |
| Aggregation pipeline (simple) | 5 ms | 30 ms | Group/sort on small set |
| $lookup (join, small) | 10 ms | 50 ms | Nested-loop equivalent |
| Text search (indexed) | 3 ms | 20 ms | Full-text index |
| Bulk insertMany (1K docs) | 20 ms | 80 ms | |

**Max throughput:** 10K–50K ops/s; WiredTiger lock contention on highly concurrent writes.

---

### ClickHouse
Column-oriented OLAP engine; terrible for OLTP patterns.

| Operation | p50 | p99 | Notes |
|-----------|-----|-----|-------|
| COUNT(*) on 1M rows | 2 ms | 15 ms | Column scan, vectorized |
| Aggregation (SUM/AVG, 1M rows) | 5 ms | 50 ms | Single column, warm |
| GROUP BY + ORDER BY (1M rows) | 10 ms | 100 ms | |
| Multi-join aggregation (1M rows) | 50 ms | 500 ms | ClickHouse prefers flat tables |
| INSERT (batch 10K rows) | 5 ms | 30 ms | Bulk insert; never single-row |
| Single-row INSERT | 10 ms | 50 ms | Anti-pattern; use batches |
| Full-text search (token match) | 5 ms | 40 ms | Bloom filter + index |
| Point lookup by primary key | 5 ms | 30 ms | Not optimized for; use OLTP DBs |

**Max throughput:** 500–10K queries/s depending on complexity; built for large analytical scans.

---

### Elasticsearch
JVM-based; Lucene inverted index.

| Operation | p50 | p99 | Notes |
|-----------|-----|-----|-------|
| Term query (exact match) | 2 ms | 15 ms | Inverted index lookup |
| Full-text search (1M docs) | 5 ms | 30 ms | BM25 scoring, 1 shard |
| Boolean query (multi-field) | 8 ms | 50 ms | Intersecting postings lists |
| Aggregation (terms, 1M docs) | 10 ms | 80 ms | Doc values scan |
| Index (single document) | 5 ms | 30 ms | Refresh interval = 1s default |
| Bulk index (1K docs) | 20 ms | 100 ms | Amortizes refresh overhead |
| Get by ID | 2 ms | 10 ms | Direct segment lookup |
| Script query | 20 ms | 200 ms | Painless scripting overhead |

**Max throughput:** 5K–20K search QPS per node; indexing can saturate I/O quickly.

---

## Message Queues

### Kafka (KRaft)
Built for sequential disk writes; throughput >> latency focus.

| Operation | p50 | p99 | Notes |
|-----------|-----|-----|-------|
| Produce (async, acks=1) | 0.1 ms | 1 ms | Batched; broker async ack |
| Produce (sync, acks=all) | 2 ms | 10 ms | Waits for all ISR replicas |
| Consume (poll, messages ready) | 0.5 ms | 3 ms | From page cache |
| Produce → Consume (end-to-end) | 5 ms | 20 ms | Single partition, acks=1 |
| Rebalance (consumer group) | 1 s | 10 s | Triggered by member join/leave |

**Max throughput:** 500K–1M messages/s per broker (sequential disk write); measured at MB/s.

---

### RabbitMQ
AMQP; persistent queues add disk latency.

| Operation | p50 | p99 | Notes |
|-----------|-----|-----|-------|
| Publish (transient) | 0.2 ms | 1 ms | In-memory only |
| Publish (persistent) | 1 ms | 5 ms | Disk journal flush |
| Publish + Consume (round-trip) | 0.5 ms | 2 ms | Transient message |
| Publish + Consume (persistent) | 2 ms | 10 ms | Both sides acknowledge disk |
| Queue depth 10K (consume) | 1 ms | 5 ms | No significant depth penalty |
| Queue depth 1M (consume) | 5 ms | 30 ms | Disk paging begins |

**Max throughput:** 20K–100K msg/s (persistent); up to 200K msg/s transient.

---

## Storage

### MinIO (local NVMe SSD)

| Operation | p50 | p99 | Notes |
|-----------|-----|-----|-------|
| GET object (1 KB) | 3 ms | 15 ms | Metadata + small read |
| GET object (1 MB) | 5 ms | 30 ms | |
| GET object (100 MB) | 100 ms | 500 ms | Throughput-bound |
| PUT object (1 KB) | 5 ms | 20 ms | |
| PUT object (1 MB) | 10 ms | 50 ms | |
| HEAD object (metadata only) | 2 ms | 10 ms | |
| List objects (100 items) | 5 ms | 25 ms | |
| Delete object | 5 ms | 20 ms | |

**Max throughput:** 500–5K req/s (small objects); limited by disk I/O and metadata ops.

---

## Networking (proxy overhead)

### Nginx (reverse proxy)

| Operation | p50 | p99 | Notes |
|-----------|-----|-----|-------|
| Proxy pass (keep-alive) | 0.2 ms | 1 ms | Amortized over connection |
| Proxy pass (new connection) | 1 ms | 5 ms | TCP handshake to upstream |
| Static file serve (1 KB) | 0.1 ms | 0.5 ms | sendfile syscall |
| SSL handshake overhead | 10 ms | 30 ms | One-time per connection (TLS 1.3) |
| Gzip compression (10 KB response) | 0.3 ms | 1 ms | CPU cost on small payloads |

**Max throughput:** 50K–200K req/s (proxy); limited by file descriptors and CPU.

---

### HAProxy (L7 load balancer)

| Operation | p50 | p99 | Notes |
|-----------|-----|-----|-------|
| LB routing overhead | 0.1 ms | 0.5 ms | L7 HTTP mode |
| LB routing overhead (TCP mode) | 0.05 ms | 0.3 ms | L4, less parsing |
| Health check interval | — | — | Configurable; default 2s |

---

## Vector Databases

### Qdrant

| Operation | p50 | p99 | Notes |
|-----------|-----|-----|-------|
| Insert vector (dim=1536) | 1 ms | 5 ms | With payload |
| ANN search (1M vecs, top-10) | 5 ms | 20 ms | HNSW index, ef=128 |
| ANN search (10M vecs, top-10) | 10 ms | 50 ms | Larger graph traversal |
| Filtered ANN (payload filter) | 8 ms | 40 ms | Depends on filter selectivity |
| Exact search (brute force) | 100 ms | 1 s | O(N·D) — avoid on large sets |
| Batch upsert (1K vectors) | 50 ms | 200 ms | |

### pgvector (PostgreSQL extension)

| Operation | p50 | p99 | Notes |
|-----------|-----|-----|-------|
| Exact kNN (100K vecs, dim=768) | 10 ms | 50 ms | Sequential scan — no index |
| ANN via ivfflat (100K, top-10) | 2 ms | 10 ms | probes=10; tune for recall/speed |
| ANN via HNSW (100K, top-10) | 1 ms | 5 ms | Better recall than ivfflat |
| Insert vector | 2 ms | 8 ms | Includes index maintenance |

### ChromaDB (embedded)

| Operation | p50 | p99 | Notes |
|-----------|-----|-----|-------|
| Add vectors (batch 100, dim=768) | 20 ms | 100 ms | SQLite-backed, in-process |
| Query (100K vecs, top-10) | 10 ms | 50 ms | HNSW in-process |

---

## LLM Inference (local hardware)

### Ollama

| Setup | Operation | p50 | Notes |
|-------|-----------|-----|-------|
| CPU, 7B (Q4) | Time to first token (TTFT) | 2–5 s | Cold start (model load) |
| CPU, 7B (Q4) | Per-token generation | 80–200 ms | ~5–12 tok/s |
| CPU, 13B (Q4) | Per-token generation | 200–500 ms | ~2–5 tok/s |
| GPU (8GB VRAM), 7B | TTFT | 0.2–0.5 s | |
| GPU (8GB VRAM), 7B | Per-token generation | 10–25 ms | ~40–100 tok/s |
| GPU (24GB VRAM), 13B | Per-token generation | 15–35 ms | ~30–65 tok/s |

**Formula:** `latency = TTFT + (output_tokens × ms_per_token)`

---

## System Calls & I/O Primitives

| Operation | Latency | Notes |
|-----------|---------|-------|
| System call overhead | ~100–300 ns | Context switch into kernel |
| File open (cached) | ~5 µs | vfs lookup |
| read() 4KB page (page cache) | ~1 µs | Memory copy only |
| read() 4KB (NVMe, cold) | ~20–100 µs | Actual disk I/O |
| write() + fsync() (4KB) | ~100 µs – 2 ms | WAL/journal durability |
| TCP send/recv (loopback) | ~10–50 µs | Kernel TCP stack |
| epoll_wait (event notification) | ~1–5 µs | |
| fork() | ~100 µs – 1 ms | Page table clone |
| Thread creation (pthread) | ~20–100 µs | |

---

## Quick Reference Card

```
Sub-millisecond:   Redis GET, HAProxy route, Kafka produce (async)
1–5 ms:           PostgreSQL PK read, Cassandra read, MongoDB findOne
5–50 ms:          Elasticsearch search, ClickHouse aggregation, MinIO GET
50–500 ms:        LLM first token (GPU), Spark query planning
> 1 second:       LLM first token (CPU), Cassandra ALLOW FILTERING
```
