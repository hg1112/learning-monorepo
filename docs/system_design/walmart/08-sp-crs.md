# Chapter 8 — sp-crs (Click Redirect Service)

## 1. Overview

**sp-crs** is the click tracking and redirect service for Walmart Sponsored Products. Every advertiser click flows through this service: it validates the click, deduplicates it (Cassandra), records it (Azure SQL), fires a Kafka event for budget and attribution downstream, and redirects the shopper to the advertiser's target URL.

- **Domain:** Click Tracking & Attribution
- **Tech:** Java 17, Spring Boot 3.5.6, Spring Kafka, Cassandra, MeghaCache
- **WCNP Namespaces:** `sp-crs-wmt`, `sp-crs-sams`
- **Port:** 8080

---

## 2. Architecture Diagram

```mermaid
graph LR
  subgraph sp-crs
    TRACK["TrackingController\n(GET /track, POST /replay)"]
    ADMIN["AdminController\n(GET /health, /isRecovery)"]
    CLICK_SVC["ClickLoggingService\n(async thread pool)"]
    DEDUP["ClickDeduplicationService\n(Cassandra TTL: 900s)"]
    BUDGET_CLIENT["BudgetClient\n(HTTP Protobuf)"]
    CAMPAIGN_CLIENT["CampaignServerClient\n(DARPA HTTP)"]
    KAFKA_PROD["KafkaProducer\n(click.topic, replay.topic)"]

    TRACK -->|"async"| CLICK_SVC
    CLICK_SVC --> DEDUP
    CLICK_SVC --> KAFKA_PROD
    CLICK_SVC --> BUDGET_CLIENT
    CLICK_SVC --> CAMPAIGN_CLIENT
  end

  CASSANDRA[("Cassandra\nClick dedup, IP tracking")]
  AZURE_SQL[("Azure SQL\nClick log, Budget tables")]
  MEGHACACHE[("MeghaCache\nBucket cache: TTL 86400s")]
  KAFKA[("Kafka\nclick.topic")]
  BUDDY["sp-buddy\n(Budget pacing)"]
  DARPA["darpa\n(Campaign metadata)"]
  SHOPPER["Shopper Browser"]
  AD_URL["Advertiser Target URL"]

  DEDUP -->|"TTL-based dedup check"| CASSANDRA
  CLICK_SVC -->|"Write click log"| AZURE_SQL
  CLICK_SVC -->|"Lookup bucket"| MEGHACACHE
  KAFKA_PROD -->|"Publish click event"| KAFKA
  BUDGET_CLIENT -->|"POST budget update (Protobuf)"| BUDDY
  CAMPAIGN_CLIENT -->|"GET campaign metadata"| DARPA

  SHOPPER -->|"GET /track?rd=...&rf=..."| TRACK
  TRACK -->|"302 Redirect"| AD_URL
```

---

## 3. API / Interface

| Method | Path | Parameters | Response | Description |
|--------|------|-----------|----------|-------------|
| GET | `/track` | `rd` (redirect URL), `rf` (flag) | 302 Redirect | Main click tracking endpoint |
| GET | `/sp/track` | Same as `/track` | 302 Redirect | Alternative click tracking path |
| POST | `/replay` | `skipReplay` flag, `TrackDetail` body | 200 OK / 400 | Replay a click event |
| GET | `/health` | — | JSON status | Kubernetes liveness |
| GET | `/isRecovery` | — | boolean | Recovery mode status |
| GET | `/budgetServerHealth` | — | health | sp-buddy connectivity |
| GET | `/campaignServerHealth` | — | health | DARPA connectivity |
| GET | `/cassandraHealth` | — | health | Cassandra connectivity |
| GET | `/dbReadHealth` | — | health | Azure SQL read health |

**Note:** All click tracking is processed **asynchronously** via a dedicated thread pool (logging executor) to minimize redirect latency.

---

## 4. Data Model

```mermaid
erDiagram
  CLICK_LOG ||--o{ DAILY_BUDGET_SPEND : "triggers"
  CLICK_LOG ||--o{ CASSANDRA_DEDUP : "checked in"
  CAMPAIGN ||--o{ DAILY_BUDGET_SPEND : "tracked per"

  CLICK_LOG {
    string clickId PK
    string campaignId
    string adGroupId
    string itemId
    string userId
    string sessionId
    timestamp clickTime
    string redirectUrl
    boolean isDuplicate
    string tenant
  }

  CASSANDRA_DEDUP {
    string clickHash PK
    timestamp expiresAt
    int count
  }

  DAILY_BUDGET_SPEND {
    string campaignId PK
    date spendDate PK
    float totalSpend
    float dailyBudget
    float remainingBudget
  }

  BUCKET_BUDGET_SUMMARY {
    string bucketId PK
    string campaignId
    float bucketSpend
    float bucketBudget
  }
```

---

## 5. Inter-Service Dependencies

```mermaid
graph TD
  sp_crs["sp-crs"]
  sp_buddy["sp-buddy\n(BudgetClient — Protobuf)"]
  darpa["darpa\n(CampaignServerClient — JSON)"]
  kafka["Kafka\n(click.topic, replay.topic)"]
  cassandra[("Cassandra\nDedup + IP tracking")]
  azure_sql[("Azure SQL\nClick logs + Budget")]
  meghacache[("MeghaCache\nBucket cache")]

  sp_crs -->|"POST /v1/budgets — Bearer Token"| sp_buddy
  sp_crs -->|"GET /demandMetadata — Bearer Token"| darpa
  sp_crs -->|"Produce click events"| kafka
  sp_crs -->|"Read/Write dedup keys (TTL 900s)"| cassandra
  sp_crs -->|"Write click log, read budget"| azure_sql
  sp_crs -->|"Cache bucket assignments"| meghacache
```

---

## 6. Configuration

| Config Key | Default | Description |
|-----------|---------|-------------|
| `clickDedup.dedup.enabled` | `false` | Enable click deduplication |
| `clickDedup.cassandra.ttl` | `900` | Dedup key TTL in seconds |
| `clickDedup.allowDupClickCount` | `2` | Allowed duplicate clicks per TTL |
| `staleClick.millisecond.threshold` | `600000` | Stale click threshold (10 min) |
| `allowed.domains` | `walmart.com, mobile.walmart.com` | Allowed redirect domains |
| `recoveryMode` | `false` | Recovery mode (skip processing) |
| `click.logging.enabled` | `true` | Enable click logging |
| `bucket.cache.ttl` | `86400` | Bucket cache TTL (seconds) |
| `multiple.experiments.enabled` | `false` | Multi-experiment per click |
| `budget.split.enabled` | `false` | Budget split across buckets |
| `kafka.click.topic` | (CCM) | Kafka topic for click events |
| `sox.enabled` | `true` | SOX compliance Hawkshaw enrichment |

---

## 7. Example Scenario — Shopper Clicks a Sponsored Product Ad

```mermaid
sequenceDiagram
  actor Shopper
  participant CRS as sp-crs /track
  participant CASSANDRA as Cassandra (dedup)
  participant SQL as Azure SQL (click log)
  participant KAFKA as Kafka (click.topic)
  participant BUDDY as sp-buddy (budget)
  participant AD_URL as Advertiser URL

  Shopper->>CRS: GET /track?rd=https://walmart.com/item/123&rf=1
  Note over CRS: Async: log click in thread pool
  CRS->>Shopper: 302 Redirect → https://walmart.com/item/123
  Shopper->>AD_URL: Browser follows redirect

  Note over CRS: Background async processing
  CRS->>CASSANDRA: Check clickHash for dedup (TTL 900s)
  CASSANDRA-->>CRS: Not found → unique click
  CRS->>CASSANDRA: Write clickHash with TTL

  CRS->>SQL: INSERT click log (clickId, campaignId, adGroupId, timestamp)
  CRS->>KAFKA: Produce click event to click.topic\n{campaignId, adGroupId, cpc, timestamp}

  Note over KAFKA: sp-buddy consumes click event
  KAFKA-->>BUDDY: Consume click event
  BUDDY->>BUDDY: Deduct CPC from daily campaign budget
```

---

## 8. Click Deduplication Flow

```mermaid
flowchart TD
  A[Click arrives] --> B{Dedup enabled?}
  B -->|No| E[Process click]
  B -->|Yes| C[Compute clickHash\nuserID + adGroupID + timestamp window]
  C --> D{Hash in Cassandra?}
  D -->|No| F[Write hash to Cassandra TTL=900s]
  F --> E
  D -->|Yes| G{Count < allowDupClickCount?}
  G -->|Yes| H[Increment count + Process]
  H --> E
  G -->|No| I[Mark as duplicate, skip Kafka]
  I --> J[Return 200, no budget impact]
```
