# System Design: Sharding

---

## The Problem

After indexing, a single Postgres node still hits hard limits:

```
orders table: 5B rows, 2 TB
Single node write throughput: saturated at ~30k inserts/sec
Storage: disk full
Replication lag: 45 seconds (replica can't keep up with WAL stream)
```

Vertical scaling (bigger machine) has a ceiling. The only path forward is **horizontal scaling** — splitting data across multiple nodes.

**Sharding** (horizontal partitioning) routes each row to exactly one node based on a shard key. No single node holds the full table.

```
  Single node (vertical scaling limit)

  ┌──────────────────────────────────┐
  │           Postgres               │
  │  orders: 5B rows, 2 TB           │      ← can't add more RAM / CPU
  │  writes: 30k/s  (maxed out)      │      ← disk I/O bottleneck
  │  VACUUM can't keep up            │
  └──────────────────────────────────┘
                   │
                   │  shard on user_id
                   ▼
  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐
  │  Shard 0 │  │  Shard 1 │  │  Shard 2 │  │  Shard 3 │
  │ user 0–  │  │ user 5M– │  │ user 10M–│  │ user 15M–│
  │ 4,999,999│  │ 9,999,999│  │14,999,999│  │19,999,999│
  │ ~500M rws│  │ ~500M rws│  │ ~500M rws│  │ ~500M rws│
  │  7.5k/s  │  │  7.5k/s  │  │  7.5k/s  │  │  7.5k/s  │
  └──────────┘  └──────────┘  └──────────┘  └──────────┘
       writes split evenly: 4 × 7.5k = 30k/s total capacity
```

---

## Sharding Strategies

### 1. Range sharding

Each shard owns a contiguous range of the shard key.

```
  shard_map (stored in ZooKeeper / config service):
  ┌──────────────────┬────────┐
  │ user_id range    │ shard  │
  ├──────────────────┼────────┤
  │      0 – 4,999,999│ shard0 │
  │  5,000,000 – 9,999,999│ shard1 │
  │ 10,000,000 –14,999,999│ shard2 │
  │ 15,000,000 –19,999,999│ shard3 │
  └──────────────────┴────────┘

  Lookup(user_id=7,432,100):
  7,432,100 falls in 5M–10M range → shard1 ✓  (single node)

  Lookup(user_id BETWEEN 4M AND 6M):
  Spans shard0 and shard1 → scatter-gather across 2 shards
```

**Pros:** Range scans land on 1–2 shards. Easy to reason about.  
**Cons:** **Hot spots** — new users always hit the highest shard; time-series data always writes to the latest range shard, leaving others cold.

---

### 2. Hash sharding

```
  shard = hash(shard_key) % num_shards

  user_id = 7,432,100  →  MurmurHash → 0x3A7F  →  0x3A7F % 4 = 3  →  shard3
  user_id = 1,000,000  →  MurmurHash → 0x91C2  →  0x91C2 % 4 = 2  →  shard2

  Distribution (ideal):
  ┌─────────────────────────────────────────────────────┐
  │  shard0: 25%  │  shard1: 25%  │  shard2: 25%  │  shard3: 25%  │
  └─────────────────────────────────────────────────────┘
```

**Pros:** Even write distribution across all shards, no hot spots.  
**Cons:** Range queries (`user_id BETWEEN 1M AND 2M`) scatter across all shards. Adding/removing shards requires rehashing (solved with consistent hashing).

---

### 3. Directory sharding (lookup table)

```
  A dedicated shard router maps each shard_key to a shard.

  ┌──────────────┐   lookup(user_id=7432100)   ┌─────────────────┐
  │  App server  │ ──────────────────────────► │  Shard Router   │
  └──────────────┘                             │  (etcd / Redis) │
                                               └────────┬────────┘
                                                        │ shard3
                                                        ▼
                                                    ┌────────┐
                                                    │ Shard 3│
                                                    └────────┘

  Router table:
  ┌─────────────┬────────┐
  │ user_id     │ shard  │
  ├─────────────┼────────┤
  │     42      │ shard3 │   ← high-value user, isolated on dedicated shard
  │  7,432,100  │ shard1 │
  │  1,000,000  │ shard2 │
  └─────────────┴────────┘
```

**Pros:** Maximum flexibility. Large tenants / celebrities can be migrated to their own dedicated shard without touching others.  
**Cons:** Shard router is a single point of failure. Every query has an extra network hop.

---

## Choosing the Shard Key

```
  BAD shard keys — cause hot spots:
  ──────────────────────────────────────────────────────────────────
  status          99% of orders are PENDING → one shard overwhelmed
  created_at      All new writes hit the latest range shard
  country_code    'US' = 40% of users → one shard overloaded
  sequential id   With range sharding: always appends to last shard

  GOOD shard keys — uniform distribution + query locality:
  ──────────────────────────────────────────────────────────────────
  user_id         High cardinality, uniform. Co-locates all of a
  (hash sharded)  user's data: orders, rides, sessions on one shard.
                  "Give me all orders for user 42" = single shard.

  tenant_id       Multi-tenant SaaS. Isolates tenants. Large tenants
                  can get dedicated shards via directory sharding.
```

**The golden rule:** shard on the column that appears in the `WHERE` clause of your most frequent queries. If 90% of queries filter on `user_id`, shard on `user_id`. All data for that user lives on one node — no cross-shard joins for the common case.

---

## Cross-Shard Queries — The Fan-Out Problem

```
  "Top 10 most expensive products across all shards"

  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐
  │  Shard 0 │  │  Shard 1 │  │  Shard 2 │  │  Shard 3 │
  │ top 10   │  │ top 10   │  │ top 10   │  │ top 10   │
  └────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘
       └──────────────────┬──────────────────────┘
                          ▼
               ┌─────────────────────┐
               │  Scatter-gather     │
               │  Merge 4×10=40 rows │
               │  Re-sort, top 10    │
               └─────────────────────┘
  Cost: 4 parallel queries + merge. Acceptable.

  Aggregations across shards:
  COUNT(*):  sum each shard's count
  SUM(x):    sum each shard's sum
  AVG(x):    (sum of shard sums) / (sum of shard counts)
             NOT: average of shard averages  ← common bug
```

Queries that **cannot** be sharded efficiently:
- **JOIN across shard keys**: `orders JOIN users` when orders sharded by `order_id`, users by `user_id` → cross-shard join. Fix: shard both tables on `user_id`.
- **DISTINCT**: must collect all values from all shards, deduplicate centrally.
- **Offset pagination**: each shard needs `OFFSET N`, results are merged — O(N) × N shards. Always use cursor pagination on sharded tables.

---

## Consistent Hashing — Adding Shards Without Rehashing Everything

```
  Naive hash: shard = user_id % 4
  Add a 5th shard → user_id % 5 → ~80% of keys move to different shards.
  Requires migrating ~4B rows. Catastrophic.

  ─────────────────────────────────────────────────────────────────

  Consistent hashing ring:

                    0° (key space start)
                         S0
                    ┌────┴────┐
              S3 ───┤  ring   ├─── S1
                    └────┬────┘
                         S2
                    180°

  Each shard owns an arc of the ring.
  key → hash → position on ring → walk clockwise → first shard encountered

  ┌────────────────────────────────────────────────────────────┐
  │  S0: 0°–90°   S1: 90°–180°   S2: 180°–270°   S3: 270°–360°│
  └────────────────────────────────────────────────────────────┘

  Add S4 between S1 (90°) and S2 (180°) at 135°:
  ┌─────────────────────────────────────────────────────────────────┐
  │  S0: 0°–90°  S1: 90°–135°  S4: 135°–180°  S2: 180°–270°  ...  │
  └─────────────────────────────────────────────────────────────────┘
  Only keys in the 90°–135° arc move from S1 → S4.
  ~12.5% of total data moves. All other shards unaffected.

  Virtual nodes: each physical shard has 150–200 positions on the ring.
  Prevents uneven arcs when shards have different capacities.
  S0 with 2× RAM gets 2× the virtual nodes → receives 2× the traffic.
```

---

## Online Resharding (Zero-Downtime Rebalance)

```
  Goal: migrate keys from old shard layout to new shard layout
        without downtime or data loss.

  Phase 1 — Dual write (start here, before any data moves)
  ┌──────────┐
  │  App     │──── write ──────► old shard  (primary source of truth)
  │          │──── write ──────► new shard  (shadow write)
  └──────────┘
  Reads still go to old shard.

  Phase 2 — Backfill (async, off-peak)
  Copy all historical rows from old shard → new shard in batches.
  Deduplicate with dual-write shadow (new shard already has recent rows).

  Phase 3 — Verify
  CRC32 / row count checksum: old shard total == new shard total.
  If mismatch: investigate before proceeding.

  Phase 4 — Read cutover
  Switch reads to new shard (feature flag / config push).
  Monitor error rate for 30 minutes.

  Phase 5 — Stop old writes
  Stop dual-writing to old shard. New shard is now the primary.

  Phase 6 — Cleanup
  Drop old shard after 30-day retention window.
  (Keep it around in case you need to roll back.)

  Timeline: Phase 2 (backfill) is the long pole — can take days for TB-scale tables.
```

---

## Architecture: Uber Orders at Scale

```
  orders table: 5B rows, 2 TB
  Sharding: hash on user_id, 256 shards
  Each shard: 1 primary + 2 read replicas (PostgreSQL streaming replication)

                              ┌───────────────────────────┐
  App servers                 │       Shard Router         │
  GET /orders?userId=42  ────►│  (etcd config, in-process  │
  POST /orders           ────►│   client lib in each pod)  │
                              └─────────────┬─────────────┘
                                            │
                          hash(42) % 256 = 7 → shard 7
                                            │
                                            ▼
                         ┌──────────────────────────────────┐
                         │             Shard 7               │
                         │                                   │
                         │  ┌───────────────────────────┐   │
                         │  │     Primary (RW)           │   │
                         │  │  Indexes:                  │   │
                         │  │  • (user_id, created_at ↓) │   │
                         │  │  • (user_id, status)       │   │
                         │  │  • (order_id) UNIQUE       │   │
                         │  └─────────────┬──────────────┘   │
                         │                │ WAL replication   │
                         │  ┌─────────────┴──────────────┐   │
                         │  │   Replica 1 (RO) — reads   │   │
                         │  │   Replica 2 (RO) — reads   │   │
                         │  └────────────────────────────┘   │
                         └──────────────────────────────────┘

  GET /orders?userId=42:
  → shard 7 replica
  → index seek on (user_id=42, created_at DESC)
  → O(log N) on ~20M rows (shard 7's share), not 5B
  → ~1ms

  POST /orders (new order for user 42):
  → shard 7 primary
  → INSERT + WAL replication to both replicas
  → ~3ms
```

---

## When to Index vs When to Shard

```
  Symptom                        Fix              Why
  ─────────────────────────────────────────────────────────────────────
  Slow reads, no index           Add B-Tree index  Full table scan
  Slow reads, index exists       Covering index    Heap fetch bottleneck
  Slow reads, covered, still slow  Read replicas  Single node read QPS saturated
  Write throughput > 50k/s       Shard            Single node I/O maxed
  Table > 500 GB–1 TB            Shard or partition  VACUUM, WAL lag issues
  Hot rows (celebrity user)      Shard + L1 cache  Sharding moves the row to one
                                                   shard but doesn't distribute
                                                   reads for that one key
  Cross-shard JOINs slow         Re-shard          Wrong shard key
```

---

## Key Rules

```
  1.  Shard key must appear in WHERE clause of most frequent queries.
  2.  High cardinality + uniform distribution → no hot spots.
  3.  Co-locate entities that are JOIN'd: shard both orders and users on user_id.
  4.  Never shard on mutable columns (status, updated_at) — key would change.
  5.  Use consistent hashing to limit data movement when adding shards.
  6.  Avoid cross-shard transactions — design the data model to make them rare.
  7.  Offset pagination across shards is O(N×S) — always use cursor pagination.
  8.  For aggregations: sum the sums, sum the counts. Never average the averages.
  9.  Resharding = dual-write → backfill → verify → cutover. Never big-bang.
  10. Indexes still matter on each shard. Sharding ≠ indexing.
```