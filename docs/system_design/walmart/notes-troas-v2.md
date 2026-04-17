# TROAS Interview Notes — Uber Round 3
> Presenter copy · **Press N in the deck to toggle the notes panel** · Keep this doc open as backup

---

## Pre-Interview Checklist
- [ ] Open `deck-troas-v2.html` in Chrome, full screen (F11), font scaling 100%
- [ ] Test slide navigation: Arrow keys, Space, N for notes
- [ ] Have this file open in a second window/monitor
- [ ] Time target: 15 min overview + 45 min TROAS + 10 min trade-offs + 5 min Q&A

---

## Slide 1 — Title
- **Hook**: "I'm going to walk through a sponsored products ad platform I've worked on deeply, with a focus on TROAS — our smart bidding system that automatically adjusts bids to hit a target return on ad spend."
- Stats to anchor the scale: <50ms p99 serving, 10M active campaigns, 1B ad requests/day, 17 services

---

## Slide 2 — Agenda
- Don't dwell here — just signal the structure
- "Happy to go deeper on any component — especially ML scoring or bidding architecture"

---

## Slide 3 — Platform Architecture (4 Planes)

### 4 planes to walk through:
1. **Tooling**: CampaignSvc (campaign CRUD), ExperimentMgr (A/B experiments), BudgetSvc (budget pacing)
2. **Indexing**: IndexPublisher (Kafka consumer, enrichment), Solr/Vespa (ad candidate index), Cassandra (eligibility), Kafka (event bus)
3. **Ad Serving**: AdGateway (GraphQL GW) → AdOrchestrator (orchestrator) → BiddingEngine (Bidding Engine, gRPC) → AuctionEngine (Auction Engine)
4. **ML Platform**: MLPlatform (ML platform), Feast + Cassandra (feature store), Triton H100 (inference cluster)

### Cross-plane flows:
- Tooling → Kafka → Indexing (campaign events, async)
- Indexing → Serving (Solr candidates, Cassandra eligibility, both dashed = async/eventual)
- Serving ↔ ML (synchronous: score request and feature fetch)

---

## Slide 4 — Ad Serving Critical Path

### The <50ms story:
- Shopper search → AdGateway (3-level cache) → AdOrchestrator (orchestrator) → parallel calls:
  - BiddingEngine (bids, gRPC, sharded)
  - AuctionEngine (auction + CandidateSvc retrieval + ML scoring)
  - BudgetSvc (budget check)
- AuctionEngine returns winner → AdGateway returns response

### Why TSP (True Second Price)?
- Incentive-compatible: advertisers bid their true value because they only pay one cent above second place
- First Price leads to bid shading (bid lower than true value), reduces auction efficiency and revenue
- eCPM = pCTR × bid × relevance → winner is ranked by expected revenue per impression

### Caffeine → Memcached → Cassandra (AdGateway 3-level cache):
- L1 (Caffeine): in-process JVM cache, sub-microsecond
- L2 (Memcached): cluster-level, ~1ms
- L3 (Cassandra): persistent, ~5ms

---

## Slide 5 — Section Break: TROAS Deep Dive
- Pause, reframe: "Now let's go deep on TROAS"

---

## Slide 6 — Why TROAS

### The problem:
- Manual CPC bidding requires per-keyword bid tuning
- At 10M campaigns × thousands of keywords = billions of bid decisions
- Even with dashboards, humans can't react to real-time signals (time-of-day, competitor bids, seasonality)
- Static bids lead to either overspending (poor ROAS) or under-bidding (missed impressions)

### TROAS value prop:
- Advertiser sets `targetRoas = 4.0` (= $4 revenue per $1 ad spend)
- System maximizes ad spend (impressions) subject to the ROAS constraint
- Adapts in real-time: day-part factors, pacing factors, exploration → optimization phases
- Exclusive to auto-bidded campaigns — one strategy at a time (TROAS or Dynamic Bidding, not both)

---

## Slide 7 — Phase State Machine

### 5 phases:

| Phase | Bid Strategy | When |
|-------|-------------|------|
| INITIALIZATION | Base bid × time × pacing | Campaign goes live, no data |
| LEARNING | Exploration (Bayesian bandit) OR ROAS-based if bidPrime available | Collecting conversion data |
| LEARNING_PAUSED | **No bid emitted — item excluded** | Budget depleted or manual pause |
| OPTIMIZATION | ML-driven bid (pCTR + pCVR from Triton) | Sufficient conversion data, models trained |
| TRANSITION | ROAS wind-down | Experiment applied or strategy switch |

### Key transitions to emphasize:
- LEARNING → OPTIMIZATION: triggered when pCVR and pVPC models have sufficient training data
- LEARNING ↔ LEARNING_PAUSED: budget events (depletion/restoration)
- OPTIMIZATION/TRANSITION → same ROAS formula as INITIALIZATION (safe conservative)
- All paths (except PAUSED) → `finalCPC = clamp(bid, $0.20, $5.00) → round(4dp)`

---

## Slide 8 — Campaign Ingestion

### Flow (async, eventually consistent):
1. Advertiser sets `targetRoas` in CampaignSvc UI
2. CampaignSvc validates (campaign LIVE, daily budget, not already TROAS, targetRoas ≥ 0.001)
3. Persists to Azure SQL campaign table
4. Publishes to Kafka `campaign-events` topic
5. IndexPublisher consumes → enriches via ItemRegistry (item registry) → multi-tenant fanout
6. Writes to Solr/Vespa: candidate index with `biddingStrategy=TROAS`, `targetRoas`, `phase`
7. Writes to Cassandra: eligibility flags, bid params pre-loaded into Bidding Engine caches

### Protobuf contract:
```
AdItemCampaignBidParam {
  double   target_roas
  enum     bidding_strategy  // STANDARD_CPC | DYNAMIC_BIDDING | TROAS
  enum     troas_phase       // INIT | LEARNING | LEARNING_PAUSED | OPT | TRANSITION
}
```

### Consistency caveat:
- Campaign edit → Solr update: ~minutes lag (Kafka + IndexPublisher processing)
- This is acceptable: the serving path reads from Solr (which has the latest indexed params)

---

## Slide 9 — Bidding Engine 3-Cache Architecture

### 3 Caffeine caches in BiddingEngine:

**Cache 1: AdGroup Bid Params** (`adgroup_bid_params` table)
- Key: `adGroupId`
- Fields: `phase`, bid weights (ROAS vs ML blend), `monetizationRatio`, `campaignStatus`
- Version source type: WEIGHTS, MR

**Cache 2: Item Bid Params** (`item_bid_params` table)
- Key: `(adGroupId, itemId)` with zero-key fallback
- Fields: `bidPrime` (base bid), `bidBounds` [$0.20, $5.00], `pacingFactor`, exploration distributions
- Version source type: BP, BB, PVPC, PF, SD

**Cache 3: DayPart Bid Params** (`day_part_bid_params` table)
- Key: `(adGroupId, date)`
- Fields: 24-element time-of-day factor vector
- At serving: `factor = vector[currentHour]`
- Must have exactly 24 elements; validation on read

### Zero-key fallback hierarchy (Item cache):
1. `(adGroupId, itemId)` — item-specific override
2. `(adGroupId, '0')` — ad-group-level default (serves most items)
3. `('0', '0')` — system-wide default
4. ConfigSvc defaults — hard-coded fallback

### ConfigSvc version validation:
- Every cache entry has a `version` field
- ConfigSvc controls the "active" version per source type (e.g., `troas.default.bidPrimeVer = "v2"`)
- On each cache read: compare stored version to ConfigSvc required version
- Mismatch → `VERSION_NOT_IN_CACHE` counter + ConfigSvc default value substituted
- Key benefit: zero-downtime rollout — stage new params in DB, flip ConfigSvc key, instant activation

---

## Slide 10 — Bid Strategy Per Phase

### Walk the decision tree:

**INITIALIZATION / TRANSITION** — Base bid formula:
- `bid = bidPrime × dayPartFactor × pacingFactor`
- bidPrime: base bid from previous optimization cycle (or CPC from campaign setup)
- dayPartFactor: time-of-day multiplier from 24-element vector
- pacingFactor: 0–1 multiplier; reduces bid aggressiveness as daily budget depletes

**LEARNING** — Two sub-paths:
1. If `enableOptAlgoForLearning=true` AND `bidPrime` available → use ROAS-based formula (same as INIT)
2. Otherwise → Bayesian bandit exploration over bid price buckets (Beta distributions)
   - 4 price buckets with Beta(α,β) parameters per bucket
   - Sample from each distribution; select price with highest sampled value
   - Over time, winning prices get higher α, losing prices get higher β → concentrates on good prices

**LEARNING_PAUSED** — No bid emitted, item excluded from auction

**OPTIMIZATION** — ML-driven bid:
- **Full-score items** (both pCVR and pVPC models available via Triton): ML formula → targetRoas constraint
- **Non-full-score items** (cold-start, missing features): ROAS-based fallback (same as INIT)

**All paths → `calculateTroasCPC(bid)`**:
- `finalCPC = round(clamp(bid, bidBound.low, bidBound.high), 4 decimal places)`
- Default bounds: $0.20 floor, $5.00 ceiling (both ConfigSvc-configurable)

---

## Slide 11 — Cache Design Decisions

### Why 3 separate caches?
- Different refresh rates: phase/weights change infrequently, item bid params change with bid cycles, day-part rotates daily
- Allows per-source-type ConfigSvc version validation — only affected source type switches on update

### Zero-key fallback — scale decision:
- Most items in an ad group share identical bid params → write one group default at `(adGroupId, '0')`
- Only items that need a per-item override get their own `(adGroupId, itemId)` row
- Result: item_bid_params table stays at O(campaigns + overrides), not O(campaigns × items)
- Startup cache pre-load goes from billions of rows to millions — BiddingEngine starts fast
- This is a design choice, not a workaround: the fallback hierarchy is the intended model

---

## Slide 12 — ML Pipeline

### Offline (daily batch):
1. BigQuery: historical click + conversion logs (training data)
2. PySpark on Dataproc: feature materialization pipeline
3. FeaturePipeline: materializes 5 FeatureViews → Cassandra daily
4. Model training: Two-Tower retrieval + DCN v2 ranking → versioned artifacts
5. Triton Model Registry: models deployed to H100 cluster

### Online (per-request, <5ms):
1. Ad request with candidates from AuctionEngine
2. Feast SDK `getOnlineFeatures()` → Cassandra `midas` keyspace → feature vector
3. 4-level cache stack:
   - L1: Caffeine (in-process, sub-μs)
   - L2: ClusterCache (cluster, ~1ms)
   - L3: Cassandra (persistent, ~5ms)
   - L4: Triton H100 (live inference, ~10ms)
4. Scores (pCTR, pCVR, pVPC) returned to AuctionEngine

### 5 FeatureViews:
- `item_features`: item metadata (category, brand, price)
- `cnsldv2`: consolidated engagement signals
- `items_rate`: historical CTR rates
- `pvpc`: predicted value per conversion (offline model)
- `item_quality`: item quality scores

---

## Slide 13 — ML Architecture Decisions

### Two-Tower retrieval:
- Ad Tower: run offline at ad creation time → 128-dim embedding stored in VectorDB/Vespa
- User Tower: run online at serving time → Triton inference → ANN search against stored ad embeddings
- Returns top-100 candidates (approximate nearest neighbor)
- Why: decouples index build from serving. 10M ads indexed offline → serving only needs to run User Tower

### DCN v2 ranking:
- Input: 32-dim feature vector (13 dense + 19 sparse embeddings)
- Cross Network: explicit feature interactions (3 layers)
- Deep Network: implicit non-linear combinations (256 → 128 → 64)
- Output: pCTR, pCVR per candidate
- These scores are the key inputs to OPTIMIZATION phase bid computation

---

## Slide 14 — Budget Pacing + Splitter

### BudgetSvc architecture:
- Azure SQL: durable daily budget state — source of truth for all spend tracking and audit trail
- Daily rollover at midnight per tenant timezone (WMT, SAMS, INTL separate cycles)
- Budget eligibility status written to Cassandra → IndexPublisher reads → Solr index updated (eventual consistency)
- Over-budget: campaign removed from index. In-budget: eligible for auction

### Budget splitter:
- Campaign index carries `budgetBucketId` (bbid) per candidate
- AdOrchestrator resolves bucket from ExperimentPlatform experiment config
- bbid flows: AdOrchestrator → BiddingEngine → AuctionEngine → click beacon
- Click beacon: `bkt = "sp_wmt_srp|bb_BUCKET_A"` — pipe + prefix
- `TROAS_SCORING_CALL{bbid, plmtck, modelId}` dimensional metric: per-bucket, per-placement tracking

---

## Slide 15 — Budget Detail

### Budget deduction flow:
- Click → ClickTracker dedup → BudgetSvc deduct → Kafka click-events (async reporting)
- Azure SQL is the authoritative daily budget ledger; all deductions are transactional

### Click deduplication (ClickTracker):
- Cassandra TTL-based dedup window: 900 seconds
- On click: check if click_id seen in last 900s (Cassandra read) → if new, write to Cassandra + deduct from BudgetSvc
- Async thread pool for tracking; redirect to product page is synchronous (user sees it instantly)

### Budget eligibility propagation lag:
- Over-budget detection → Cassandra write → IndexPublisher read → Solr update: ~minutes
- Small window of overspend is acceptable vs. complexity of synchronous enforcement
- Trade-off: 99.9% of campaigns are within budget; 0.1% at-the-limit campaigns may overspend by a small margin

---

## Slide 16 — Experiment Strategy

### Why experiments first?
- Advertisers are risk-averse about auto-bidding. "What if the system overbids?"
- Experiment lets them see real data: ROAS lift, revenue delta, impression share

### Flow:
1. Advertiser creates experiment in CampaignSvc UI (POST /experiments)
2. CampaignSvc validates, clones control campaign → test campaign
3. Budget split equally: 50% control (original bidding), 50% test (TROAS bidding)
4. Runs N days (ConfigSvc-configured min/max)
5. READY_TO_REVIEW: reports generated from Azure Reports
6. Advertiser reviews reports → POST /experiments/{id}/apply
7. APPLIED: control campaign migrated to TROAS permanently; test campaign archived

### Validation rules (important to mention):
- Campaign must be LIVE (not paused/ended)
- Must have daily budget (not lifetime budget)
- No other active experiments on the same campaign
- targetRoas ≥ minTargetRoas (default 0.001 — prevents zero/near-zero targets)
- Not offsite campaigns (offsite TROAS is different product)

---

## Slide 17 — Safety Guards

### Philosophy: never crash an auction over missing parameters
- Every missing parameter has a fallback value
- Every fallback triggers a metric counter
- Sustained high counter rates → PagerDuty alert

### Key guards to highlight:
- `NO_BP`: bidPrime missing → ConfigSvc default value for bidPrime; if still absent, skip item
- `ZERO_TR`: targetRoas = 0 guard before ML formula (division by zero protection)
- `UNKNOWN_PHASE`: unrecognized enum value → treat as INITIALIZATION (safe conservative fallback)
- `TIMEOUT`: async processTroasRequest timeout → item excluded from response (don't hold up the auction)
- `VERSION_NOT_IN_CACHE`: expected during deployments; ConfigSvc defaults maintain system health

### Protobuf guards:
- bidParamsNode absent: item falls back to standard CPC bidding
- JSON parse failure: partially populated params → individual field fallbacks activated

---

## Slide 18 — Trade-offs + Key Design Decisions

### Platform-level decisions:

**TSP vs First Price auction:**
- First Price requires bid shading strategies from advertisers → complex, unstable
- TSP: dominant strategy is bid your true value → simpler for advertisers, stable equilibrium
- Industry standard: Google, Meta, Amazon all use second-price variants

**Sharded gRPC bidding engine:**
- Hot campaign = one campaign ID getting majority of traffic during launch event
- Monolith: hot campaign blocks bid computation for all other campaigns
- Sharding by campaign ID: hot campaigns isolated to their shard

**Cassandra vs Redis for Feast:**
- Cassandra: cheap per GB, TTL support (features expire naturally), compaction handles aging data
- Redis: faster but ~10x more expensive per GB at scale

**Ad index: Solr + Vespa vs Elasticsearch:**
- Vespa supports native vector ANN + BM25 hybrid in one system
- Elasticsearch requires a separate ANN plugin; operational overhead higher
- Solr retained for keyword/filter retrieval path while Vespa handles vector search

**3-tier Caffeine (JVM) for bid param cache vs Memcached/Redis:**
- In-process: zero serialization, zero network hop, sub-microsecond access
- Redis/Memcached would add ~1ms per read × 3 caches × thousands of items per request
- Trade-off: requires startup pre-load + periodic refresh, but this is cheap compared to latency savings

### TROAS-specific decisions:

**Bayesian bandit for LEARNING phase exploration:**
- Alternative: random price sampling or ε-greedy
- Bayesian (Beta distributions per price bucket): self-correcting — good prices accumulate high α, bad prices accumulate high β
- Converges faster than uniform random; no static ε to tune
- Key: runs even without bidPrime → works from day one of a new campaign

**ConfigSvc version validation per source type:**
- Each cache entry carries a `version`; ConfigSvc holds the "active" required version
- Mismatch → use ConfigSvc default value + emit `VERSION_NOT_IN_CACHE` counter
- Enables zero-downtime rollout: stage new params in DB → flip one ConfigSvc key → instant activation
- Per-source-type granularity (BP, BB, PVPC, PF, SD, WEIGHTS, MR): only affected params switch; unrelated params unaffected

**Zero-key fallback hierarchy (item params):**
- Decision: most items share identical bid params → write one group default at `(adGroupId, '0')`
- Only per-item overrides get `(adGroupId, itemId)` rows
- Keeps item_bid_params table at O(campaigns + overrides), not O(campaigns × items)
- Fallback chain: item-specific → group default → system default → ConfigSvc hardcoded default

**Universal bid clamp + 4dp rounding:**
- `finalCPC = round(clamp(bid, floor, ceiling), 4 decimal places)`
- Prevents runaway bids from any phase, any ML error, any formula edge case
- Floor default $0.20 (prevents zero/near-zero bids that win nothing), ceiling $5.00 (prevents budget blow-out)
- Both ConfigSvc-configurable per placement type → same engine handles different ad surfaces

**TROAS requires daily budget, not lifetime:**
- Lifetime budget has no per-day constraint → pacingFactor undefined (no daily cap to pace against)
- Daily budget enables: pacingFactor (daily spend / daily cap), overnight rollover, per-day ROAS measurement
- Validation rule enforced at experiment creation: blocks lifetime-budget campaigns from TROAS

**Campaign cloning for A/B experiments:**
- Control campaign = original (unchanged bidding strategy)
- Test campaign = clone with TROAS bidding + separate budgetBucketId
- Why clone rather than split traffic at auction time: clean attribution, no bid interference between strategies, advertiser can see both campaigns in their dashboard

**bidParamsNode JSON (not Protobuf) for bid param transport:**
- Decision: pass TROAS params from BiddingEngine to AuctionEngine as JSON node inside existing Protobuf message
- Alternative: extend Protobuf schema directly
- Reasoning: avoids Protobuf schema migration across two separately-deployed services; JSON node is opaque to intermediaries; backwards-compatible (absent node = standard CPC fallback)

### Open problems:
1. **Cold-start convergence**: new campaigns wait for bidPrime to accumulate. Exploration phase can overbid
2. **Observability**: OpenTelemetry traces don't cross Kafka async boundaries → can't measure end-to-end latency for ingestion path
3. **CandidateSvc (candidate retrieval)**: Solr → Vespa migration incomplete → two systems to maintain

---

## Slide 19 — Q&A Summary

### Likely follow-up questions:

**"How do you handle the cold-start problem for new campaigns?"**
- New campaign starts in INITIALIZATION phase
- bidPrime sourced from CPC value set by advertiser at campaign creation
- As the campaign runs and conversion data accumulates, bidPrime gets updated from the optimization cycle
- Meanwhile, LEARNING phase uses Bayesian bandit exploration over price buckets — this works even without bidPrime

**"How do you prevent runaway spending if the ML model makes bad predictions?"**
- Universal bid clamp: $0.20 floor, $5.00 ceiling (ConfigSvc-configurable per placement)
- Budget pacing factor: as daily budget depletes, pacingFactor drops from 1.0 to 0 → bids naturally reduce
- BudgetSvc deducts on every confirmed click; Azure SQL tracks daily spend authoritatively
- Over-budget flag → Cassandra → IndexPublisher removes campaign from index
- Experiment first: advertisers validate TROAS works for their campaign before committing

**"What happens if Triton goes down?"**
- Serving falls back to non-full-score path: ROAS-based bid formula without ML inputs
- 4-level cache means Triton is only called on cache miss (~small % of traffic at steady state)
- Circuit breaker in MLPlatform: if Triton latency exceeds SLO, bypass to Cassandra-cached scores

**"How does the experiment budget split work technically?"**
- ExperimentMgr creates two budget buckets (BUCKET_A = control, BUCKET_B = test)
- Campaign index carries `budgetBucketId` for each candidate
- On click: click beacon appends `|bb_BUCKET_A` or `|bb_BUCKET_B`
- Reporting reads these tags and aggregates spend/revenue per bucket separately

**"Why not use Redis for the Bidding Engine caches instead of Caffeine?"**
- Caffeine is in-process (JVM): no serialization, no network hop, sub-microsecond access
- Redis would add ~1ms per cache read × 3 caches × thousands of items per request = significant latency
- Trade-off: Caffeine requires cache pre-load at startup and periodic refresh, but this is acceptable

---

## Critical Numbers to Memorize

| Metric | Value |
|--------|-------|
| Ad serving p99 | <50ms |
| Active campaigns | 10M+ |
| Ad requests/day | 1B (~12K QPS) |
| Clicks/day | 100M |
| ML inference cache levels | 4 |
| Triton replicas | 16 across 3 regions |
| Feast FeatureViews | 5 |
| TROAS phases | 5 |
| Caffeine caches in BiddingEngine | 3 |
| Default bid floor | $0.20 |
| Default bid ceiling | $5.00 |
| Click dedup TTL | 900 seconds |
| Embedding dimensions | 128 |

---

*Last updated: April 2026 · Uber Round 3 · Palash Kasodhan*
