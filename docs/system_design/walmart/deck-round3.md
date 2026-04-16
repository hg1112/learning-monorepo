---
marp: true
theme: default
paginate: true
size: 16:9
style: |
  section {
    font-size: 20px;
    padding: 40px 60px;
  }
  h1 { color: #0066cc; font-size: 38px; }
  h2 { color: #0055aa; font-size: 30px; border-bottom: 2px solid #0055aa; padding-bottom: 6px; }
  table { font-size: 17px; width: 100%; }
  th { background: #0055aa; color: white; padding: 6px 10px; }
  td { padding: 5px 10px; }
  tr:nth-child(even) { background: #f0f4ff; }
  code { font-size: 15px; background: #f4f4f4; }
  pre { font-size: 15px; background: #f4f4f4; padding: 12px; border-radius: 6px; }
  blockquote { border-left: 4px solid #0066cc; padding-left: 12px; color: #444; }
---

# Sponsored Products Ad Platform

### System Design — Round 3
**Harish G · Uber Engineering · April 2026**

> *"Self-serve advertiser tooling + sub-50ms real-time ad serving at scale"*

---

## Agenda — 75 min

| # | Topic | Time |
|---|-------|------|
| 1 | Problem + Requirements | 15 min |
| 2 | High-Level Architecture | 12 min |
| 3 | Advertiser Tooling + Indexing | 10 min |
| 4 | Ad Serving Critical Path | 12 min |
| 5 | ML Scoring + Feature Store | 10 min |
| 6 | Budget Pacing + Click Tracking | 8 min |
| 7 | Trade-offs + Open Problems | 8 min |
| — | Q&A | ~5 min |

---

## The Problem — Two Users, One Platform

```
ADVERTISER                              SHOPPER
────────────────────────                ──────────────────────────
• Wants to promote SKUs                 • Browses search/browse pages
• Sets daily budget ($50/day)           • Sees relevant sponsored ads
• Bids per keyword or item              • Clicks → product page
• Tracks ROI / ROAS                     • (Unaware of the auction)
           │                                          │
           └──────────────── PLATFORM ────────────────┘
                       Connect them via auction
                       in < 50ms per ad request
                       without overspending budgets
```

**Core tension:** Relevance (shoppers) vs Revenue (advertisers) vs Scale (platform)

---

## Functional Requirements

**Advertiser Plane**
- Campaign CRUD: campaigns → ad groups → keywords → media → brand assets
- Bid management: manual CPC, Smart Bidding (TROAS), A/B experiments
- Reporting: impressions, clicks, CTR, spend, ROAS

**Serving Plane**
- Ad retrieval: keyword + item-based candidate retrieval from index
- Ranking: ML-scored relevance (predicted CTR)
- Auction: True Second Price (TSP) winner selection

**Click & Budget**
- Click deduplication + redirect tracking (SOX-compliant)
- Real-time budget enforcement: daily cap + pacing
- Midnight rollover per tenant timezone

---

## Non-Functional Requirements + Scale

| Requirement | Target | Rationale |
|---|---|---|
| Ad serving latency | **< 50ms p99** | Shopper search SLA |
| Throughput | **~12K QPS** | 1B ad requests/day |
| Active campaigns | **10M+** | WMT + SAMS + INTL |
| Budget accuracy | **At-most-once billing** | Advertiser trust |
| Click dedup window | **900s TTL** | Bot / accidental click protection |
| Feature freshness | **Daily** (batch pipeline) | ML training cadence |
| Multi-tenancy | **WMT, SAMS, INTL** | Separate indexes + budgets + rollover |

> **Key constraint:** Budget deduction must be strongly consistent — overshooting a $50 daily budget is a billing error that erodes advertiser trust.

---

## Platform Lifecycle — 11 Steps

```
  ADVERTISER CREATES CAMPAIGN
  1.  darpa ─────── Campaign CRUD + persist to Oracle / Azure SQL
  2.  sp-ace ─────── A/B experiment bucket assigned; budget split via sp-buddy
  3.  darpa ─────── Kafka: publish campaign-events

  INDEXING (async)
  4.  radpub-v2 ─── Consume Kafka → IRO enrichment → Solr/Vespa index
  5.  radpub-v2 ─── Write eligibility flags to Cassandra

  SHOPPER SEARCHES
  6.  midas-spade ── Receive request; parallel async fan-out
  7.  midas-spector ─ Compute bids per campaign (sharded gRPC, Memcached)
  8.  abram ─────── Retrieve top-100 from Solr; run TSP auction
  9.  sparkle + davinci ── Relevance scoring via Feast features + Triton H100

  CLICK → BUDGET
  10. sp-crs ─────── Cassandra dedup (TTL 900s) → Kafka SOX click-events
  11. sp-buddy ───── Budget deduction → pause campaign if daily cap hit
```

---

## System Context — C4 Level 1

```
┌────────────────────────────────────────────────────────────────────────┐
│                    SP Advertising Platform  (WCNP / AKS)               │
│                                                                        │
│  ┌───────────────┐   ┌─────────────────┐   ┌──────────────────────┐   │
│  │  Advertiser   │   │    Indexing      │   │   Ad Serving Core    │   │
│  │    Plane      │──▶│    Pipeline      │──▶│   + ML Scoring       │   │
│  │ darpa, sp-ace │   │  radpub-v2       │   │  spade, abram,       │   │
│  │ marty         │   │  Solr / Vespa    │   │  sparkle, davinci    │   │
│  └───────────────┘   └─────────────────┘   └──────────────────────┘   │
│                                                        │               │
│  ┌─────────────────────────────────────────────────── ▼ ─────────────┐ │
│  │                  Click & Budget Plane   (sp-crs, sp-buddy)        │ │
│  └───────────────────────────────────────────────────────────────────┘ │
└────────────────────────────────────────────────────────────────────────┘
        ▲                                                   ▲
   Advertiser                                          Shopper
   (API / AI Agent)                             (Walmart.com search)

External: Oracle/Azure SQL · Cassandra · Solr/Vespa · Kafka · Triton H100
```

---

## High-Level Architecture — 4 Planes

```
[Advertiser Plane]                [Indexing Pipeline]
  marty (AI Agent / LangGraph)      radpub-v2 (Kafka consumer)
  darpa (Campaign CRUD)   ──Kafka──▶  │
  sp-ace (A/B experiments)            ├──▶ Solr / Vespa (ad index)
                                      └──▶ Cassandra (eligibility)

[Ad Serving Core]                 [ML Scoring]
  swag ──▶ midas-spade              ├──▶ sparkle (relevance scorer)
  (GraphQL GW)  │                   │      └──▶ sp-adserver-feast (Feast)
             fan-out                └──▶ davinci (ML cache + router)
                ├──▶ midas-spector (bids, gRPC)     └──▶ element-davinci (H100)
                ├──▶ abram (TSP auction)
                └──▶ [ML Scoring above]

[Click & Budget]
  sp-crs (click dedup + redirect) ──Kafka SOX──▶ sp-buddy (budget pacing)
```

> All serving fan-out calls are **parallel async** (AsyncHttpClient / WebFlux) — this is how we hit < 50ms p99.

---

## Advertiser Tooling

**darpa** — Campaign Management (Scala 2.12 / Play 2.7 / Akka)
- 121 REST routes: campaigns → ad groups → keywords → media → brand assets
- Oracle DB (primary) + Azure SQL (reporting); publishes `campaign-events` Kafka on every mutation

**sp-ace** — A/B Experimentation (Java 17 / Spring Boot 3.5.7)
- State machine: `DRAFT → UNDER_REVIEW → APPROVED → RUNNING → PAUSED → COMPLETED`
- Splits budget buckets between control / treatment via sp-buddy
- Enables safe rollout of bid strategies, ranking models, UI layout changes

**marty** — AI Advertiser Agent (Python 3.11 / FastAPI / LangGraph 0.5)
```
LangGraph state machine:
  IdMapperNode ──▶ RouterAgentNode ──▶ ArgoAgentNode (perf metrics)
                                   └──▶ WalsaAgentNode (seller items)
                                              └──▶ ResponseGeneratorNode
Azure OpenAI o3-mini via Element LLM Gateway
Natural language ──▶ structured darpa API calls
```

---

## Indexing Pipeline

**radpub-v2** — Campaign Indexer (Java 17 / Spring Boot / Spring Cloud Stream)

```
Kafka: campaign-events
        │
        ▼
  radpub-v2 consumes (processCampaignMessage-in-0)
        │
        ├──▶ IRO (Item Enrichment API)  ← title, price, category, imageUrl
        │
        ├──▶ Solr / Vespa ← AdItemSolrDoc (keyword match + metadata)
        │       Multi-tenant cores: WMT / SAMS / INTL
        │       Enables keyword + category-based candidate retrieval
        │
        └──▶ Cassandra: INSERT ad_item_eligibility
                        (campaign_id, ad_item_id, eligible=true)
```

**Key design principle:** Decouple campaign mutation (synchronous HTTP) from index build (async via Kafka).
New ad is **searchable within seconds** of creation, not blocking the create API.

---

## Ad Serving Critical Path

```
Shopper ──▶ swag (Apollo Federation GraphQL GW, 3-level cache)
                 │
                 └──▶ midas-spade (orchestrator, AsyncHttpClient)
                              │
              ┌───────────────┼─────────────┬─────────────────┐
              │               │             │                 │
       midas-spector       abram         davinci           sp-buddy
       (bids, gRPC)    (TSP auction)   (ML cache)        (budget check)
              │               │             │
         Azure SQL          Solr        Cassandra L3
        +Memcached        (100 cands)      │ MISS
                               │       Triton H100 (8ms)
                           sparkle
                        (relevance scores)
                        sp-adserver-feast
                         (Feast + Cassandra)
                              │
                 abram: TSP ──▶ top-N ads returned
```

**p99 target: < 50ms end-to-end** | All downstream calls run in parallel

---

## Auction Deep Dive — True Second Price (TSP)

**Candidate Retrieval** — abram → Solr/Vespa (Darwin/AdGenie)
- Keyword + category match on indexed campaigns
- Returns top-100 candidates per request

**Scoring Formula:**
```
  effectiveScore  = pCTR × bid_amount × relevance_score

  winner's charge = second_highest_bid + $0.01   ← TSP
```

**Why TSP over First Price?** Incentive-compatible — advertisers bid their true value.
First-price leads to underbidding, shading, and auction instability.

**Bid computation** — midas-spector (sharded gRPC)
- Shard pattern: 1 orchestrator + N shard nodes (4–8 in prod)
- Hot campaigns isolated to their shard — don't starve small advertisers
- Memcached for bid snapshots; Azure SQL is source of truth

---

## Caching Strategy

**3-Level Cache** — swag (ad aggregation gateway)
```
L1: Caffeine (in-process)  ─  ~10μs
L2: Memcached (distributed)─  ~1ms
L3: Cassandra (multi-region)─ ~5ms
```

**4-Level Cache** — davinci (ML vector router)
```
L1: Caffeine         ~10μs   ← most requests land here
L2: MeghaCache       ~1ms    ← cross-pod distributed cache
L3: Cassandra        ~5ms    ← multi-region, TTL 24h
L4: Triton H100      ~8ms    ← gRPC inference (full miss only)
```

> **Key insight:** Cache hit rate directly controls GPU cost.
> At 90% L1 hit rate, only 10% of requests reach H100s.
> Without this chain, 16 H100 pods would not be enough at 12K QPS.

**Budget eligibility cache** — sp-buddy writes to Cassandra; serving reads it.
Avoids a real-time budget DB hit on every ad serving request.

---

## ML Scoring — Two-Tower Retrieval

**Problem:** 10M+ indexed ads — can't score all of them per request.

**Solution: Asymmetric Two-Tower Architecture**

```
OFFLINE (at ad creation time):              ONLINE (at serving time):
──────────────────────────────              ───────────────────────────
Ad Tower                                    User Tower
  │                                           │
  ├── item text, title, description           ├── user session signals
  ├── category, price, image features         ├── query tokens
  └──▶ Triton inference                       └──▶ Triton (<5ms)
        └──▶ 128-dim unit vector                    └──▶ 128-dim vector
              Stored in Qdrant / Vespa                ANN search → top-100 ads
```

**Why Two-Tower?**
- Ad embeddings precomputed offline → no recomputation at serving time
- ANN search (Approximate Nearest Neighbor) is O(log N), not O(N)
- Scales linearly with ad catalog size

---

## ML Scoring — DCN v2 Ranker

**Input:** Top-100 retrieved candidates → rank by predicted CTR

```
Features (32-dim input):
  Dense  (13):  bid, budget_ratio, position, CTR_history, quality_score ...
  Sparse (19):  item_category, advertiser_id, query_tokens, time_of_day ...
                              │
            ┌─────────────────▼───────────────────┐
            │          Cross Network (3 layers)    │  explicit feature crosses
            │          (bid × category, etc.)      │  (price × CTR_history, ...)
            └─────────────────┬───────────────────┘
                              │
            ┌─────────────────▼───────────────────┐
            │         Deep Network                 │  learned representations
            │         256 → 128 → 64               │
            └─────────────────┬───────────────────┘
                         P(click) ∈ [0, 1]
```

**Auction score:** `effectiveCPM = fixedBidCpm × predictedCTR`

**Infra:** 16 H100 (80GB) replicas across SCUS / WUSE2 / EUS2 (~8ms per batch)

---

## Feature Store — Feast + Cassandra

**Offline pipeline** — element-adserver-feast (runs daily via Looper scheduler)
```
BigQuery  (200M+ ad event rows)
    │
    └──▶ Dataproc PySpark cluster (28 executors × 20GB)
               │
               ├──▶ GCS Parquet staging
               └──▶ Cassandra  midas keyspace  (TTL ~182 days)
                      5 FeatureViews:
                        item_features, cnsldv2, items_rate, pvpc, item_quality
```

**Online serving** — sp-adserver-feast (Java 17 + Feast SDK)
```
sparkle / davinci
    └──▶ sp-adserver-feast
              └──▶ CQL: SELECT feature_name, value
                        FROM midas.{feature_view}
                        WHERE entity_key = ?   (<10ms)
```

**Why Feast + Cassandra over Redis?**
Cassandra's TTL + compaction fits the 182-day feature lifecycle naturally.
Redis would require manual key eviction management at this scale.

---

## Budget Pacing — sp-buddy

**Challenge:** 12K QPS of clicks → budget deductions. Hitting the DB on every click doesn't scale.

```
Kafka: click-events (SOX cluster)
    │
    └──▶ sp-buddy (Kafka Streams + Java 17)
               │
               ├──▶ Redis Lua atomic DECR       ← sub-ms, no race conditions
               │        DECRBY budget:{campaign_id} {amount}
               │        IF remaining ≤ 0 → flag BUDGET_EXHAUSTED
               │
               └──▶ Azure SQL                   ← durable daily state
                        UPDATE daily_spend += amount
```

**On budget exhaustion:**
1. sp-buddy → darpa: `POST /internal/campaigns/{id}/pause`
2. darpa → Kafka: `campaign-events (PAUSED)`
3. radpub-v2: marks campaign ineligible in Cassandra
4. **Midnight rollover:** Azure SQL reset daily_spend=0 → resume campaigns

**Multi-tenant:** WMT / WAP-MX / WAP-CA have separate budget state + rollover timezone

---

## Click Tracking — sp-crs

**Challenge:** Same user clicks same ad twice (back button, bots). Must charge advertiser only once per valid click.

```
Shopper clicks tracking URL
    │
    └──▶ sp-crs (Java 17 / Spring Boot 3.5.6)
               │
               ├──▶ Cassandra: SELECT WHERE click_id = ?  (TTL 900s window)
               │
               │  NEW click (not found):
               ├──▶ Cassandra: INSERT click_id USING TTL 900
               ├──▶ Kafka SOX: publish ClickEvent { ad_item_id, campaign_id, amount }
               └──▶ HTTP 302 Redirect → walmart.com/product/{id}

               DUPLICATE (within 900s):
               └──▶ HTTP 302 Redirect  (no charge, no Kafka event)
```

**SOX cluster:** Separate dedicated Kafka cluster for click billing events — audit trail for financial compliance.

**Async thread pool:** Tracking is non-blocking; redirect is always immediate.

---

## Trade-offs & Key Decisions

| Decision | Chosen | Alternative | Why |
|---|---|---|---|
| Auction type | **True Second Price** | First Price | Incentive-compatible; advertisers bid true value |
| Bidding engine | **Sharded gRPC** (spector) | Monolith | Hot campaigns isolated; gRPC < 2ms |
| Feature store | **Feast + Cassandra** | Redis | 182-day TTL; compaction fits lifecycle |
| Ad index | **Solr + Vespa** | Elasticsearch | Vespa native vector + BM25 hybrid in one store |
| Budget deduction | **Redis Lua atomic** | DB transactions | 200K writes/sec; DB serialization can't keep up |
| Click dedup | **Cassandra TTL** | Redis SET NX | Multi-region replication built-in; 900s window |
| ML inference | **4-level cache + H100** | CPU-only | 8ms GPU vs 50ms+ CPU at batch; cache amortizes GPU cost |
| Async events | **Kafka** (SOX + GCP) | REST callbacks | Decoupled; replay on failure; SOX audit trail for billing |

---

## What I'd Do Differently / Open Problems

1. **Darwin retrieval gap** — Solr → Vespa migration incomplete.
   Darwin blends candidates from both via manually tuned weights, not a learned blend.
   → Should A/B test Vespa-only retrieval to simplify and reduce operational surface.

2. **spector bottleneck** — Azure SQL as bid source.
   At 100M+ active bids, this becomes a read hotspot.
   → Migrate bid store to Cassandra with row-level Memcached caching.

3. **TROAS cold-start** — Thompson Sampling (Beta distribution α/β update) needs
   ~500 impressions before bids are meaningful. New advertisers get poor ROAS early.
   → Add a warm-start from similar-category campaign priors.

4. **Trace gap across Kafka** — OpenTelemetry spans don't cross async Kafka boundaries.
   Can't trace a campaign creation all the way to its first auction win.
   → Propagate trace context via Kafka headers (W3C TraceContext).

5. **Single-task ranker** — DCN v2 predicts CTR only.
   Multi-task model (CTR + CVR) planned for May 2026 — until then, ROAS optimization is limited.

---

## Deployment — Multi-Region on Azure AKS

```
WCNP (Azure Kubernetes Service)
│
├── South Central US (Primary)
│     midas-spade · midas-spector · abram · davinci · sparkle · sp-crs · sp-buddy
│     element-davinci  H100 × 6 pods
│
├── West US 2
│     midas-spade · midas-spector · abram · davinci · sparkle
│     element-davinci  H100 × 5 pods
│
├── East US 2
│     midas-spade · midas-spector · abram · davinci · sparkle
│     element-davinci  H100 × 5 pods
│
└── Shared (single-region)
      darpa · sp-ace · radpub-v2 (WMT/SAMS/INTL) · sp-adserver-feast
      marty · marty-fe · element-ss-inference (V100 × 1, legacy)

Config: CCM2 / Tunr  |  Secrets: Akeyless  |  CI/CD: Concord
Batch: Looper + GCP Dataproc  |  Metrics: Prometheus + Grafana
Tracing: OpenTelemetry 1.49  |  API Docs: SpringDoc OpenAPI
```

---

## Summary — Key Design Principles

1. **Decouple writes from reads** — Kafka separates campaign mutation (sync) from indexing (async)
2. **Cache aggressively, invalidate precisely** — 4-level cache in davinci; eligibility cache in Cassandra
3. **Shard at natural boundaries** — spector sharded by campaign; Citus/Cassandra sharded by entity_key
4. **Budget consistency > performance** — Redis Lua for atomic deduction even at 200K writes/sec
5. **ML at every layer** — retrieval (Two-Tower ANN), ranking (DCN v2), bidding (TROAS Thompson Sampling)
6. **Multi-tenant by design** — WMT/SAMS/INTL isolated at index, budget, and Kafka cluster level
7. **Fail open for serving** — sp-buddy unavailable → assume ACTIVE (never stop showing ads)

---

## Q&A

**Good areas to go deeper:**

- **TROAS** — Thompson Sampling for smart bidding: how α/β updates, LEARNING → OPTIMIZING phase
- **Darwin** — Ad retrieval service: Solr vs Vespa vs Polaris blend, `useDavinci` flag rollout
- **InSSPire roadmap** — Multi-task CTR+CVR model, pairwise ranking A/B, TTBv3 revision timeline
- **DaVinci deep-dive** — Model versioning, offline cluster provisioning, automation pipeline (WIP)
- **Feast design** — Multi-tenant feature repos (WMT vs WAP), schema evolution, TTL strategy
- **Budget pacing algorithm** — Smoothed pacing to avoid front-loading daily budget

---

*Round 3 · Uber Engineering Design Interview*
*Harish G · April 2026*

<!-- 
SPEAKER NOTES — TIME GUIDE
Slide 1 (Title): 30s intro — "I'll walk through the Sponsored Products Ad Platform I worked on at Walmart"
Slide 2 (Agenda): 30s — set expectations for the 75 min
Slide 3 (Problem): 2min — anchor on the two users and the core tension
Slide 4 (Func Req): 2min — walk through 3 planes; ask if Palash wants to add anything
Slide 5 (NFR): 2min — emphasize the budget accuracy constraint as the hard part
Slide 6 (Lifecycle): 2min — this is the "big picture" — reference back to this throughout
Slide 7 (C4 L1): 1min — quick orientation
Slide 8 (4 Planes): 2min — name-drop services, explain why each plane exists
Slide 9 (Tooling): 3min — darpa, sp-ace, marty; focus on the Kafka publish on mutation
Slide 10 (Indexing): 2min — emphasize async decoupling via Kafka
Slide 11 (Serving path): 3min — emphasize parallel fan-out; this is the latency-critical section
Slide 12 (Auction): 3min — TSP formula, why not first price; spector shard pattern
Slide 13 (Caching): 2min — 4-level chain; cache hit rate = GPU cost
Slide 14 (Two-Tower): 2min — offline ad tower, online user tower; ANN search
Slide 15 (DCN v2): 2min — walk through the architecture; effectiveCPM formula
Slide 16 (Feast): 2min — offline vs online split; Cassandra TTL rationale
Slide 17 (Budget): 2min — Redis Lua; exhaustion → rollover flow
Slide 18 (Click): 1min — Cassandra dedup TTL; SOX Kafka
Slide 19 (Trade-offs): 2min — hit 3-4 rows; invite Palash to challenge any
Slide 20 (Open Problems): 2min — show self-awareness; mention May 2026 CVR model
Slide 21 (Deployment): 1min — 3 Azure DCs; 16 H100s
Slide 22 (Summary): 1min — 7 principles, quick recap
Slide 23 (Q&A): remainder
-->
