# QPS Calculations — Sustainable Throughput Reference

Formulas and worked examples for estimating, measuring, and planning the
throughput of backend services and ML infrastructure.

---

## Core Laws

### 1. Little's Law (the most important formula in systems design)

```
L = λ × W

Where:
  L = average number of requests in the system (concurrency)
  λ = arrival rate (QPS / throughput)
  W = average time a request spends in the system (latency in seconds)

Rearranged:
  QPS   = Concurrency / Latency_seconds
  Conc. = QPS × Latency_seconds
  Lat.  = Concurrency / QPS
```

**Example:** 200 concurrent connections to PostgreSQL, average query time 4 ms:
```
QPS = 200 / 0.004 = 50,000 queries/second
```

**Example:** You want 10,000 QPS at 20 ms average latency. How many threads do you need?
```
Concurrency = 10,000 × 0.020 = 200 threads
```

---

### 2. Thread / Worker Pool QPS Ceiling

```
Max QPS = num_workers / avg_latency_seconds

Where:
  num_workers    = thread pool size, worker count, or goroutine limit
  avg_latency    = average time a worker is busy serving one request
```

**Example:** Spring Boot with 200 server threads, average handler time 10 ms:
```
Max QPS = 200 / 0.010 = 20,000 req/s
```

**Rule:** If you want N× more QPS at the same latency, you need N× more workers (or N× lower latency).

---

### 3. Database Connection Pool QPS

```
Max DB QPS = pool_size / avg_query_time_seconds
```

**Example:** HikariCP pool of 50 connections, 2 ms average query:
```
Max DB QPS = 50 / 0.002 = 25,000 queries/s
```

**Example:** Your app needs 5,000 DB queries/s at 5 ms average. Min pool size:
```
pool_size = 5,000 × 0.005 = 25 connections
Add 20% headroom → 30 connections
```

---

### 4. Utilization = Throughput × Service Time

From queuing theory (M/M/1):

```
ρ (utilization) = λ / μ = QPS × avg_service_time_seconds

Queue length (at utilization ρ):
  L = ρ / (1 - ρ)

Mean wait time:
  W = ρ / (μ × (1 - ρ))
```

**Key insight:** At 70% utilization (ρ = 0.7), queue length ≈ 2.3× the number of servers.
At 90% utilization, queue length ≈ 9×. **Design for 60–70% utilization.**

---

### 5. Back-of-Envelope from Daily Traffic

```
Sustained QPS = daily_requests / 86,400

Peak QPS = sustained_QPS × peak_factor
             peak_factor = 2–10× (use 5× for typical web traffic)
```

**Example:** 100 million requests/day:
```
Sustained = 100,000,000 / 86,400 ≈ 1,160 QPS
Peak (5×) = 5,800 QPS
```

---

### 6. Capacity Planning (Number of Nodes)

```
Required nodes = ceil( peak_QPS / (per_node_QPS × target_utilization) )

target_utilization = 0.6–0.7 (leave 30–40% headroom for spikes)
```

**Example:** Need 50,000 peak QPS; each PostgreSQL node handles 10,000 QPS at 70%:
```
Nodes = ceil(50,000 / (10,000 × 0.7)) = ceil(7.14) = 8 nodes
```

---

## Per-Service Sustainable QPS (Single Node, Local NVMe SSD)

| Service | Simple ops QPS | Complex ops QPS | Primary bottleneck |
|---------|---------------|-----------------|-------------------|
| **Redis** | 100K–500K | 50K–200K | CPU (single-threaded cmd) |
| Redis (pipelined) | 500K–1M+ | — | Network bandwidth |
| **PostgreSQL** | 5K–20K | 500–5K | WAL I/O, connection overhead |
| **Cassandra** | 20K–100K (write) | 10K–50K (read) | Commit log, compaction |
| **MongoDB** | 10K–50K | 2K–20K | WiredTiger locking, I/O |
| **ClickHouse** | 1K–10K | 100–1K | CPU (vectorized aggregation) |
| **Elasticsearch** | 5K–20K | 500–5K | JVM heap, I/O |
| **Kafka** | 500K–1M msg/s | — | Sequential disk write |
| **RabbitMQ** | 20K–100K | — | Disk journal (persistent) |
| **MinIO** | 500–5K | — | Disk I/O, metadata |
| **Nginx** (proxy) | 50K–200K | — | File descriptors, CPU |
| **HAProxy** | 100K–500K | — | Connection table |
| **Qdrant** | 100–500 | 50–200 | HNSW graph traversal, CPU |

**"Simple ops"** = point reads/writes, single-key operations, small payloads.  
**"Complex ops"** = scans, aggregations, multi-index queries, large payloads.

---

## LLM Inference QPS

### Single Request Latency Formula

```
Response latency = TTFT + (output_tokens × time_per_token)

TTFT (time to first token) = model_load (cold) + prefill_time
prefill_time ≈ input_tokens / prefill_throughput
```

### Single-Request QPS

```
Requests/second = 1 / response_latency_seconds
```

**Example:** GPU, 7B model, 50ms TTFT, 200 output tokens at 20ms/token:
```
Latency = 0.05 + (200 × 0.020) = 4.05 seconds
QPS     = 1 / 4.05 ≈ 0.25 req/s (single request at a time)
```

### Batched Inference QPS (vLLM / continuous batching)

With batch size B and continuous batching:

```
Throughput (tokens/s) = batch_size × tokens_per_request / batch_latency_seconds

Effective req/s      = Throughput / avg_output_tokens
```

**Example:** GPU handles batch of 8, 200 tokens each, in 2 seconds:
```
Tokens/s = 8 × 200 / 2 = 800 tok/s
Req/s    = 800 / 200   = 4 req/s
```

### GPU Memory Required

```
Memory (GB) = model_params_billion × bytes_per_param × (1 + kv_cache_factor)

bytes_per_param:
  fp32  → 4 bytes (rarely used for inference)
  fp16/bf16 → 2 bytes
  int8  → 1 byte
  int4  → 0.5 bytes (GGUF Q4 quantization)

kv_cache_factor ≈ 0.1–0.3 (depends on context length and batch size)
```

| Model | fp16 | int8 | int4 (Q4) |
|-------|------|------|-----------|
| 7B | ~14 GB | ~7 GB | ~3.5 GB |
| 13B | ~26 GB | ~13 GB | ~6.5 GB |
| 30B | ~60 GB | ~30 GB | ~15 GB |
| 70B | ~140 GB | ~70 GB | ~35 GB |

**Rule:** int4 quantized 7B model fits in a consumer 6GB GPU with room for KV cache.

---

## Throughput vs Latency Tradeoffs

### The Hockey Stick (why you need headroom)

```
At utilization ρ, mean response time multiplier vs uncongested:
  ρ = 50% → 2× latency increase
  ρ = 70% → 3.3× latency increase
  ρ = 80% → 5× latency increase
  ρ = 90% → 10× latency increase
  ρ = 95% → 20× latency increase
```

This is why you design for 60–70% utilization and autoscale before hitting 80%.

### Amdahl's Law (parallelization ceiling)

```
Speedup(N) = 1 / (S + (1-S)/N)

Where:
  S = fraction of work that must be serial (cannot be parallelized)
  N = number of parallel workers
```

**Example:** If 10% of your request is serial (S=0.1), max speedup with infinite cores = 10×.
At 16 cores → Speedup = 1 / (0.1 + 0.9/16) = 1 / (0.1563) ≈ 6.4×

---

## SLA / Percentile Calculations

### Percentile from throughput and latency

```
p99 requests will complete within:
  p99_latency ≈ mean_latency × (1 + 4.6 × coefficient_of_variation)
  (assumes log-normal distribution — common for service latencies)
```

### Rule of Four Nines (tail latency budget)

```
For a request that calls N independent services:
  System p99 latency ≈ max(individual p99 latencies)

For N services called serially:
  P(all succeed at ≤ L) = P(svc1 ≤ L) × P(svc2 ≤ L) × ... × P(svcN ≤ L)

  If each service has p99 = L:
    Probability request completes ≤ L = 0.99^N

  N=10: 0.99^10 ≈ 0.904 → effectively p90, not p99
  → To keep p99 when calling 10 services: each must be at ~p999
```

**Lesson:** Fan-out amplifies tail latency. Minimize serial dependencies.

---

## Worked Examples

### Example 1 — REST API capacity planning

> Goal: 100K RPS peak on a stateless API, 20ms average latency.

```
1. Concurrency needed (Little's Law):
   L = 100,000 × 0.020 = 2,000 concurrent connections

2. Each JVM (Spring Boot) handles 200 threads at 20ms → 10,000 RPS
   Nodes = ceil(100,000 / (10,000 × 0.7)) = ceil(14.3) = 15 nodes

3. DB query rate (assuming 2 DB calls per request):
   DB QPS = 100,000 × 2 = 200,000 queries/s
   At 2ms per query with HikariCP:
   Pool per node = 200,000/15 × 0.002 = ~27 connections → round to 30

4. Redis cache hit rate 80%:
   Cache misses → DB = 200,000 × 0.2 = 40,000 QPS to DB
   Revised pool per node = 40,000/15 × 0.002 ≈ 6 → use 10 for headroom
```

---

### Example 2 — Kafka pipeline throughput

> Goal: Process 1 million events/minute at peak.

```
1. Events/second = 1,000,000 / 60 ≈ 16,667 events/s

2. With 10ms processing per event per consumer:
   Workers needed = 16,667 × 0.010 = 167 consumer threads

3. Partitions = max(consumers, throughput/partition_throughput)
   Each partition handles ~100K msg/s safely
   Partitions for throughput = ceil(16,667 / 100,000) = 1
   Partitions for parallelism = 167 consumers → use 200 partitions

4. Storage: 1KB per event, 7-day retention:
   Storage = 1,000 msg/min × 60 × 24 × 7 × 1KB = ~10 GB/day
   With RF=3: ~30 GB/day across cluster
```

---

### Example 3 — Vector search capacity

> Goal: 1,000 ANN search QPS on 10M vectors (dim=768).

```
1. Single Qdrant node handles ~100–200 QPS at 10M vectors (HNSW, NVMe)

2. Nodes needed = ceil(1,000 / (150 × 0.7)) = ceil(9.5) = 10 nodes

3. Memory per node (10M × 768 dims × 4 bytes per float):
   Vectors only = 10M × 768 × 4 = 30.7 GB
   HNSW graph overhead ≈ 2–4× vector size = 60–120 GB total
   → Use nodes with 64–128 GB RAM or use quantization (reduces to 10–20 GB)

4. With int8 quantization (4× compression):
   Effective memory = 30.7 / 4 ≈ 7.7 GB for vectors + ~15 GB for HNSW graph
   → Fits in 32 GB RAM per node → 10 nodes × 32 GB = cost-effective
```

---

## Formulas Quick Reference

```
Little's Law:            QPS = Concurrency / Latency_s
Thread pool max:         QPS = threads / latency_s
DB pool max:             QPS = pool_size / query_time_s
Target utilization:      ρ = 0.6 – 0.7 (never exceed 0.8 in steady state)
Headroom nodes:          N = ceil(peak_QPS / (node_QPS × 0.7))
Daily → sustained QPS:   QPS = daily_requests / 86,400
Peak factor:             peak_QPS = sustained × 5  (typical web; adjust per traffic pattern)
GPU memory (fp16):       GB = params_B × 2
GPU memory (int4):       GB = params_B × 0.5
LLM latency:             ms = TTFT + output_tokens × ms_per_token
LLM batch throughput:    tok/s = batch_size × output_tokens / batch_latency_s
```
