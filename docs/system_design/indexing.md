# System Design: Indexing

---

## The Problem

A single Postgres node running an e-commerce catalog:

```
products table: 500M rows, 200 GB

GET /api/products?category=Electronics   →  8 seconds (full table scan)
GET /api/orders?userId=42                →  12 seconds (full table scan)
```

**Indexing** makes queries fast on a single node by maintaining a sorted copy of key columns so Postgres can seek instead of scan.

---

## What an Index Is

A B-Tree index is a sorted copy of one or more columns stored separately from the heap (the actual table rows). A lookup costs O(log N); without an index, Postgres reads every row.

```
  products heap (unordered, ~200 GB)          B-Tree index on (category, price)
  ┌─────┬─────────────┬───────┐               ┌──────────────────────────┐
  │  id │ category    │ price │               │        ROOT PAGE          │
  ├─────┼─────────────┼───────┤               │  ≤Books | ≤Elec | ≤Sports │
  │   1 │ Clothing    │ 19.99 │               └────────────┬─────────────┘
  │   2 │ Electronics │ 99.99 │                            │
  │   3 │ Food        │  4.99 │            ┌───────────────┼───────────────┐
  │   4 │ Electronics │ 49.99 │            ▼               ▼               ▼
  │   5 │ Sports      │ 29.99 │   ┌──────────────┐ ┌──────────────┐ ┌──────────────┐
  │   6 │ Electronics │199.99 │   │ INTERNAL     │ │ INTERNAL     │ │ INTERNAL     │
  │  ...│ ...         │  ...  │   │ Books        │ │ Electronics  │ │ Sports       │
  └─────┴─────────────┴───────┘   └──────┬───────┘ └──────┬───────┘ └──────┬───────┘
                                         ▼                 ▼                 ▼
                                  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐
                                  │  LEAF PAGE   │◄►│  LEAF PAGE   │◄►│  LEAF PAGE   │
                                  │(Books, 7.99) │ │(Elec, 49.99) │ │(Sprts, 9.99) │
                                  │  → TID(1,3)  │ │  → TID(3,7)  │ │  → TID(2,1)  │
                                  │(Books,14.99) │ │(Elec, 99.99) │ │(Sprts,29.99) │
                                  └──────────────┘ └──────────────┘ └──────────────┘
                                         Leaf pages are doubly-linked for range scans

  WHERE category='Electronics' ORDER BY price ASC
  1. Root: navigate to Electronics subtree            ← 2 comparisons
  2. Internal: find price ≥ 0 leaf                   ← 1 comparison
  3. Leaf: scan forward (49.99 → 99.99 → 199.99…)   ← sequential, fast
  4. Heap fetch per TID to get remaining columns

  Total: O(log N + K)   N = total rows, K = matching rows
```

---

## Index Types

| Type | Best for | Example query |
|------|----------|---------------|
| **B-Tree** (default) | Equality, range, `ORDER BY` | `WHERE cat='Elec' AND price < 100` |
| **Hash** | Equality only — slightly faster than B-Tree | `WHERE user_id = 42` |
| **GIN** | Full-text search, array `@>`, JSONB keys | `WHERE tags @> '{sale}'` |
| **GiST** | Geometry, range overlap, nearest-neighbor | PostGIS distance queries |
| **BRIN** | Huge append-only tables with physical ordering | Time-series (`created_at`) |
| **Partial** | Subset of rows | `WHERE status = 'ACTIVE'` |
| **Covering** | Avoid heap fetch on hot paths | `INCLUDE (name, price)` |

---

## Level 1: The MVP — Single-Column Index

**Interviewer:** "Query `WHERE category='Electronics'` takes 8 seconds. Fix it."

**Candidate:**
"Add an index on `category`. Postgres will seek the B-Tree instead of scanning 500M rows."

```sql
CREATE INDEX idx_products_category ON products (category);

-- Before: Seq Scan on products  (cost=0..9M rows=500M)  8000ms
-- After:  Index Scan on idx_products_category  (rows=50k)  12ms
```

---

**Interviewer:** "Now `WHERE category='Electronics' ORDER BY price ASC` is still slow. The index didn't help the sort. Why?"

**Candidate:**
"A single-column index on `category` can filter the rows but cannot eliminate the sort. After the index returns 50k matching rows, Postgres must load them all and sort in memory — a filesort.

```
  With only idx_products_category:

  Index scan (category='Electronics')  →  50,000 rows  →  in-memory sort on price
                                                             ↑ filesort: O(K log K)

  At 50k rows: fast.  At 5M rows: out-of-memory sort spills to disk → slow.
```

The fix is a **composite index** with `category` first (equality) and `price` second (ORDER BY):"

```sql
-- Wrong: two separate indexes (Postgres picks one, then sorts)
CREATE INDEX idx_cat    ON products (category);
CREATE INDEX idx_price  ON products (price);

-- Right: composite index — equality column first, ORDER BY column second
CREATE INDEX idx_cat_price ON products (category ASC, price ASC);

-- EXPLAIN now shows: Index Scan (no sort step)
-- Query: WHERE category='Electronics' ORDER BY price ASC
-- → single seek to Electronics/price=0 leaf → scan forward → done
```

**Composite index column order rule:**
1. Equality filter columns first (highest cardinality first if multiple)
2. Range or `ORDER BY` columns last
3. ASC/DESC direction must match the query's `ORDER BY`

---

## Level 2: Covering Indexes — Eliminating Heap Fetches

**Interviewer:** "Even with the composite index, EXPLAIN shows heap fetches. Why, and how do you fix it?"

**Candidate:**
"After finding a matching entry in the index leaf, Postgres must fetch the full row from the heap (the table) to get columns that aren't in the index — a random I/O for every matching row.

```
  Index lookup path WITH heap fetch:

  B-Tree leaf                 Heap (table pages, scattered on disk)
  ┌───────────────────┐       ┌─────────────────────────────────────┐
  │ (Elec, 49.99)     │──────►│ page 847, row 3: id=4, name="USB-C  │
  │   TID=(847,3)     │       │   Hub", category="Electronics",      │
  │ (Elec, 99.99)     │──────►│   price=49.99, imageUrl="s3://..."   │
  │   TID=(203,11)    │       │ page 203, row 11: id=2, ...          │
  │ (Elec, 199.99)    │──────►│ page 1902, row 5: id=6, ...         │
  └───────────────────┘       └─────────────────────────────────────┘
         ↑ sequential                   ↑ random I/O per row (slow on HDD,
           (fast)                         acceptable on NVMe, bad at high K)
```

A **covering index** embeds extra columns directly in the leaf node. The query is answered entirely from the index — zero heap access.

```sql
-- Standard: heap fetch for every row (name, imageUrl not in index)
CREATE INDEX idx_cat_price
    ON products (category, price);

-- Covering: name and imageUrl stored in the leaf → Index Only Scan
CREATE INDEX idx_cat_price_cover
    ON products (category, price)
    INCLUDE (name, imageUrl);

EXPLAIN SELECT name, price FROM products
WHERE category = 'Electronics' ORDER BY price ASC;
-- → Index Only Scan on idx_cat_price_cover
-- Heap Fetches: 0
```

Trade-off: the index is larger on disk. Use `INCLUDE` only for columns that appear in frequent, hot `SELECT` lists."

---

## Level 3: Specialized Indexes

### Partial index — index only the rows that matter

```sql
-- 99% of orders are COMPLETED. Queries almost always filter on PENDING.
-- Indexing all 2B rows wastes space and slows writes.

CREATE INDEX idx_orders_pending
    ON orders (user_id, created_at DESC)
    WHERE status = 'PENDING';

-- Only ~10M rows indexed (the PENDING ones).
-- Queries with WHERE status='PENDING' use this index.
-- Queries without the predicate fall back to a full index scan — acceptable
-- if they're rare (batch jobs, reporting).
```

### GIN index — full-text and array search

```sql
-- Search product descriptions
ALTER TABLE products ADD COLUMN search_vector tsvector;
CREATE INDEX idx_products_fts ON products USING GIN (search_vector);

UPDATE products SET search_vector =
    to_tsvector('english', name || ' ' || description);

-- Query: full-text search
SELECT * FROM products WHERE search_vector @@ to_tsquery('wireless & headphones');
-- → Bitmap Index Scan on idx_products_fts  (< 5ms)
```

### BRIN index — time-series tables

```sql
-- events table: 10B rows, append-only, physically ordered by created_at
-- B-Tree on created_at would be 50 GB. BRIN stores only min/max per 128-page range.

CREATE INDEX idx_events_brin ON events USING BRIN (created_at);
-- Index size: ~1 MB  (vs 50 GB B-Tree)
-- Works because: pages written in timestamp order → physical and logical order match
-- Limitation: useless if rows are inserted out of order
```

---

## When Indexes Hurt

```
  Write amplification:

  INSERT INTO products (name, category, price, status, created_at) VALUES (...)

  Without indexes: 1 heap write
  With 4 indexes:  1 heap write + 4 index page writes = 5 I/Os per INSERT

  ┌───────────────────────────────────────────────────────┐
  │ Index count  │ INSERT cost  │ Throughput impact        │
  ├──────────────┼──────────────┼──────────────────────────┤
  │ 0 indexes    │ 1 I/O        │ baseline                 │
  │ 3 indexes    │ 4 I/Os       │ ~3x slower writes        │
  │ 8 indexes    │ 9 I/Os       │ ~8x slower writes        │
  └──────────────┴──────────────┴──────────────────────────┘

  OLTP tables: rarely need > 5–6 indexes.
  Find dead indexes: SELECT * FROM pg_stat_user_indexes WHERE idx_scan = 0;
  Drop them.
```

---

## EXPLAIN output — reading the plan

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT name, price FROM products
WHERE category = 'Electronics' AND price BETWEEN 50 AND 200
ORDER BY price ASC
LIMIT 10;
```

```
Index Only Scan using idx_cat_price_cover on products
  (cost=0.57..8.62 rows=10)
  (actual time=0.043..0.081 rows=10 loops=1)
  Index Cond: ((category = 'Electronics') AND (price >= 50) AND (price <= 200))
  Heap Fetches: 0                    ← covering index in use
  Buffers: shared hit=4              ← served from buffer cache
Planning Time: 0.3 ms
Execution Time: 0.1 ms
```

Key terms:
- `Seq Scan` — full table scan, no index used → add an index
- `Index Scan` — index used but heap fetched → consider covering index
- `Index Only Scan` — heap not touched → optimal
- `Heap Fetches: 0` — covering index serving all columns
- `Buffers: shared hit` — data in memory (fast); `read` = disk I/O

---

## Key Rules

```
  1.  Equality columns first in composite index; range/ORDER BY last.
  2.  ASC/DESC direction must match the query's ORDER BY direction.
  3.  Use INCLUDE (covering index) for hot SELECT lists to eliminate heap fetch.
  4.  Partial index (WHERE status='ACTIVE') when data is heavily skewed.
  5.  GIN for full-text search and array/JSONB containment.
  6.  BRIN for huge append-only tables with natural physical ordering.
  7.  Every foreign key column should have an index (speeds up JOIN + ON DELETE).
  8.  Drop unused indexes: pg_stat_user_indexes WHERE idx_scan = 0.
  9.  OLTP: aim for ≤ 5–6 indexes per table. More → write amplification.
  10. EXPLAIN ANALYZE is the ground truth — always verify the plan.
```