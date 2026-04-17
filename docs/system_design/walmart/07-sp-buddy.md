# Chapter 7 — sp-buddy (Budget Service)

## 1. Overview

**sp-buddy** is the budget management and pacing service for Walmart Sponsored Products. It ensures campaigns don't overspend their allocated daily and total budgets, handles budget splits across A/B experiment buckets, and exposes budget status APIs consumed by the ad serving pipeline during auction time.

- **Domain:** Budget Pacing & Financial Controls
- **Tech:** Java 17, Spring Boot 3.5.6, Protobuf APIs, Hibernate Envers, Spring WebClient
- **WCNP Namespaces:** `sp-buddy-wmt`, `sp-buddy-wap-mx`, `sp-buddy-wap-ca`
- **Port:** 8080

---

## 2. Architecture Diagram

```mermaid
graph LR
  subgraph sp-buddy
    BUDGET_CTRL["CampaignBudgetController\n(/v1/budgets/campaigns — Protobuf)"]
    BUCKET_CTRL["BudgetBucketController"]
    EXP_CTRL["BudgetExperimentController"]
    SPEND_CTRL["AdvertiserSpendLimitController"]
    REPLAY_CTRL["ClickReplayController"]
    HOUSEKEEP["HousekeepingController"]
    BUDGET_SVC["Budget Service Layer"]
    DARPA_SVC["DarpaService\n(WebClient)"]
    KAFKA_PROD["KafkaProducer\n(sp-budget-access-logs)"]
    STREAM["Kafka Streams\n(CPM processing)"]
    REPO["JPA Repositories\n(Spring Data)"]
    ENVERS["Hibernate Envers\n(audit)"]

    BUDGET_CTRL --> BUDGET_SVC
    BUCKET_CTRL --> BUDGET_SVC
    BUDGET_SVC --> DARPA_SVC
    BUDGET_SVC --> KAFKA_PROD
    BUDGET_SVC --> REPO
    REPO --> ENVERS
    STREAM --> REPO
  end

  DARPA["darpa\n(Campaign Service)"]
  AZURE_SQL[("Azure SQL\nCampaign/Daily budgets")]
  KAFKA[("Kafka\nAccess logs + CPM events")]
  MEMCACHED[("Memcached\nBudget cache")]

  DARPA_SVC -->|"WebClient HTTP"| DARPA
  REPO -->|"JPA"| AZURE_SQL
  KAFKA_PROD -->|"sp-budget-access-logs"| KAFKA
  STREAM -->|"sp-budget-cpm-events"| KAFKA
  BUDGET_SVC -->|"Budget cache"| MEMCACHED
```

---

## 3. API / Interface

| Method | Path | Protocol | Description |
|--------|------|----------|-------------|
| POST | `/v1/budgets/campaigns` | Protobuf | Create campaign budget |
| PUT | `/v1/budgets/campaigns` | Protobuf | Update campaign budget |
| PUT | `/v1/budgets/campaigns/rollback` | Protobuf | Rollback budget update |
| POST | `/v1/budgets/split` | JSON | Split budget across experiment buckets |
| GET | `/v1/budgets/campaigns/{campaignId}/status` | JSON | Get campaign budget status (v1 async) |
| GET | `/v2/budgets/campaigns/{campaignId}/status` | JSON | Get campaign budget status (v2) |
| PUT | `/v1/budgets/fix/campaigns/{campaignId}` | JSON | Fix budget discrepancies |
| POST | `/v1/budgets/reallocate` | Protobuf | Reallocate daily budget |
| **POST** | **`/v1/budgets/experiments/validate-splits`** | **JSON** | **Validate budget splits for an experiment (CARADS-41748)** |
| GET | `/health` | JSON | Kubernetes health check |

**Protobuf contract:** Uses `sp-api-proto:0.0.17` shared proto definitions (`CampaignBudgetCreateRequest`, `DailyCampaignBudgetCreateRequest`).

### Budget Splits Validation (CARADS-41748 — live in prod, Mar 2026)

`POST /v1/budgets/experiments/validate-splits` — validates whether the campaigns in a budget
experiment have been correctly split. Returns a `BudgetValidationResponse` with:

| Field | Type | Description |
|-------|------|-------------|
| `splitCount` | int | Number of campaigns that have been correctly budget-split |
| `unsplitCount` | int | Number of campaigns that remain unsplit |
| `campaignIds` | List\<String\> | Campaign IDs in the experiment |

**`BudgetValidatorService`** performs batch campaign budget lookup via the repository layer and
checks each campaign's split status. The multiple-budget experiment feature (parallel budget
validation across buckets) is now **fully enabled in prod and prod-RO**.

---

## 4. Data Model

```mermaid
erDiagram
  CAMPAIGN_BUDGET ||--o{ DAILY_CAMPAIGN_BUDGET : "broken into"
  CAMPAIGN_BUDGET ||--o{ CAMPAIGN_BUDGET_STATUS : "has"
  CAMPAIGN_BUDGET ||--o{ CAMPAIGN_BUDGET_AUD : "audited in"
  BUDGET_BUCKETS ||--o{ CAMPAIGN_BUDGET : "links"
  BUDGET_BUCKETS ||--o{ BUDGET_BUCKETS_AUD : "audited in"

  CAMPAIGN_BUDGET {
    string campaignId PK
    string bucketId
    float totalBudget
    float dailyBudget
    float rolloverBudget
    float adSpend
    string status
    timestamp updatedAt
  }

  DAILY_CAMPAIGN_BUDGET {
    string campaignId PK
    string bucketId PK
    date effectiveDate PK
    float dailyBudget
    float rolloverBudget
    float adSpend
  }

  BUDGET_BUCKETS {
    string bucketId PK
    string experimentId
    string label
    float allocationPercent
    boolean active
  }

  CAMPAIGN_BUDGET_STATUS {
    string campaignId PK
    string status
    timestamp updatedAt
  }
```

---

## 5. Inter-Service Dependencies

```mermaid
graph TD
  sp_buddy["sp-buddy"]
  darpa["darpa\n(DarpaService — WebClient)"]
  sp_ace["sp-ace\n(BuddyClient caller)"]
  sp_crs["sp-crs\n(BudgetClient caller)"]
  abram["abram\n(Budget check caller)"]
  kafka["Kafka\n(access logs, CPM events)"]
  azure_sql[("Azure SQL")]
  memcached[("Memcached")]

  sp_ace -->|"POST/PUT /v1/budgets"| sp_buddy
  sp_crs -->|"POST /v1/budgets (Protobuf)"| sp_buddy
  abram -->|"GET /v1/budgets/{id}/status"| sp_buddy

  sp_buddy -->|"fetchPendingDues\nliveCampaigns\nbudgetStatus"| darpa
  sp_buddy -->|"Produce access logs"| kafka
  sp_buddy -->|"Consume CPM events (stream profile)"| kafka
  sp_buddy -->|"JPA read/write"| azure_sql
  sp_buddy -->|"Budget cache"| memcached
```

---

## 6. Configuration

| Config Key | Description |
|-----------|-------------|
| `darpa.*` | DARPA service URLs and timeouts |
| `ace.*` | ACE service config for experiment integration |
| `kafka.*` | Kafka broker, topic, SSL config |
| `stream.*` | Kafka Streams settings (CPM processing) |
| `cache.*` | Memcached configuration |
| `spring.profiles.active` | `dev`, `prod`, `prod-readonly`, `stream` |

---

## 7. Example Scenario — Budget Status Check During Auction

```mermaid
sequenceDiagram
  participant ABRAM as abram (Auction Engine)
  participant BUDDY as sp-buddy
  participant SQL as Azure SQL
  participant MEMCACHED as Memcached

  Note over ABRAM: During real-time auction, check if campaign has budget
  ABRAM->>BUDDY: GET /v2/budgets/campaigns/{campaignId}/status
  BUDDY->>MEMCACHED: Check budget cache
  alt Cache hit
    MEMCACHED-->>BUDDY: {status: ACTIVE, remaining: 45.23}
  else Cache miss
    BUDDY->>SQL: SELECT daily_budget, ad_spend FROM DAILY_CAMPAIGN_BUDGET\nWHERE campaignId=? AND effectiveDate=TODAY
    SQL-->>BUDDY: {dailyBudget: 100.00, adSpend: 54.77}
    BUDDY->>MEMCACHED: Write cache (TTL short)
    BUDDY-->>ABRAM: {status: ACTIVE, remaining: 45.23}
  end
  Note over ABRAM: Include campaign in auction (has budget)
```

---

## 8. Budget Reallocation Flow (Daily Rollover)

```mermaid
sequenceDiagram
  participant SCHED as Scheduled Job (sp-buddy)
  participant DARPA as darpa
  participant SQL as Azure SQL
  participant KAFKA as Kafka

  Note over SCHED: Daily midnight UTC — rollover job
  SCHED->>DARPA: GET /liveCampaigns (fetch active campaigns)
  DARPA-->>SCHED: [campaignId, dailyBudget, rolloverEnabled]

  loop For each campaign
    SCHED->>SQL: SELECT adSpend, remainingBudget FROM yesterday
    SCHED->>SQL: INSERT DAILY_CAMPAIGN_BUDGET (new day + rollover)
    SCHED->>DARPA: PUT /budgetStatus {campaignId, status: ACTIVE}
  end

  SCHED->>KAFKA: Produce budget reallocation events (sp-budget-access-logs)
```
