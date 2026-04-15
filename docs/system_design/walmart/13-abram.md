# Chapter 13 — abram (Auction-Based Realtime Ad Matching)

## 1. Overview

**abram** (Auction-Based Realtime Ad Matching) is the **core auction engine** for Walmart Sponsored Products. It retrieves ad candidates from Solr/Vespa, scores them with **sparkle**, fetches embedding vectors from **davinci**, runs a True Second Price (TSP) auction, and returns a ranked set of winning ads. It is the deepest and most computationally intensive service in the ad serving stack.

AbRAM = **Auction based Realtime Ad Matching** — Walmart's real-time ad serving platform.

**Core Responsibilities:**
- Request Handling: orchestrates multiple external services
- Service Integration: calls Darwin (retrieval), Sparkle (scoring), Spector (bidding)
- Feature Enrichment: fetches product metadata from Cassandra and Azure SQL
- Bidding & Pacing: applies TROAS, PCVR dynamic bidding and budget pacing
- Ranking & Auction: combines relevance scores with bids for true second-price auction
- Deduplication: removes duplicate ads, applies variant grouping
- Response Construction: formats top-N ads with tracking metadata

**Key Capabilities:**
- Multi-Surface Support: SP, SB (Sponsored Brands), SV (Sponsored Videos), SD (Sponsored Deals), FS (Federated Search), Scan & Go
- Sub-second latency: p99 < 1000ms (target p99 < 500ms)
- High configurability: surface-specific and placement-specific configuration
- Resilience: circuit breakers, timeouts, graceful degradation

- **Domain:** Real-Time Auction & Ad Ranking
- **Tech:** Scala 2.12 + Java 17, Play Framework 2.7, Akka, Slick (DB), Kafka
- **WCNP Namespace:** `abram-service` (also deployed as `sp-abram-wmt`)
- **Port:** 9001 (exposed as 8080 via ingress)
- **Repo:** `gecgithub01.walmart.com/labs-ads/abram`
- **Regions:** SCUS, EUS2, WUS2

---

## 2. Architecture Diagram

```mermaid
graph LR
  subgraph abram
    SP_V3["SponsoredProductsController\nGET /v3/sp/search"]
    SB_V3["SponsoredBrandsController\nGET /v3/sb/search"]
    SV_V3["SponsoredVideosController\nGET /v3/sv/search"]
    FS_V3["FederatedSearchController\nPOST /v3/fs/search"]
    HEALTH["HealthCheckController\nGET /v3/live"]

    CAMPAIGN_CLIENT["CampaignServiceClient\n(WSClient — Play HTTP)"]
    SPARKLE_CLIENT["AsyncScoringServiceClient\n(Play WS + Protobuf)"]
    DARWIN_CLIENT["AsyncDarwinClient\n(candidate retrieval)"]
    ADGENIE_CLIENT["AdGenieRetrievalClient\n(alternative retrieval)"]

    JDBC_DAO["JDBC DAOs\n(Slick — Azure SQL)\nAdItemCatalog, Keyword, FeatureMeta"]
    CASS_DAO["Cassandra DAOs\n(AdItemVariantScoreCache, etc.)"]

    AUCTION["TrueSecondPriceAuction\n(Breeze numerics)"]
    KAFKA_LOG["KafkaLogger\n(feature + request logs)"]

    SP_V3 --> CAMPAIGN_CLIENT
    SP_V3 --> SPARKLE_CLIENT
    SP_V3 --> DARWIN_CLIENT
    SP_V3 --> AUCTION
    SP_V3 --> KAFKA_LOG
    AUCTION --> JDBC_DAO
    AUCTION --> CASS_DAO
  end

  SOLR_VESPA[("Solr / Vespa\nAd candidates")]
  SPARKLE["sparkle\n/v3/scores"]
  DAVINCI["davinci\n/v3/vector"]
  BUDDY["sp-buddy\n(budget check)"]
  CAMPAIGN_SVC["darpa\n(Campaign metadata)"]
  KAFKA[("Kafka\nfeature logs, GCP logs")]
  AZURE_SQL[("Azure SQL")]
  CASSANDRA[("Cassandra\nScore + variant cache")]

  DARWIN_CLIENT -->|"Candidate retrieval"| SOLR_VESPA
  SPARKLE_CLIENT -->|"POST /v3/scores (Protobuf)"| SPARKLE
  SP_V3 -->|"GET /v3/vector"| DAVINCI
  CAMPAIGN_CLIENT -->|"Bearer token HTTP"| CAMPAIGN_SVC
  SP_V3 -->|"Budget check"| BUDDY
  JDBC_DAO --> AZURE_SQL
  CASS_DAO --> CASSANDRA
  KAFKA_LOG --> KAFKA
```

---

## 3. API / Interface

| Method | Path | Description |
|--------|------|-------------|
| GET | `/v3/sp/search` | Sponsored Products ad search |
| POST | `/v3/sng/search` | SP negative keyword search |
| GET | `/v3/sb/search` | Sponsored Brands search |
| GET | `/v3/sv/search` | Sponsored Video search |
| GET | `/v3/sd/search` | Sponsored Deals search |
| POST/GET | `/v3/fs/search` | Federated Search |
| GET | `/v3/live` | Liveness probe |
| GET | `/v3/healthcheck` | Readiness probe |

**Key request parameters:** `query`, `userId`, `tenant`, `pageContext`, `moduleLocation`, `targetingType`
**Response models:** `SponsoredProductsResponse`, `SponsoredBrandsResponse`, `SponsoredVideosResponse`

---

## 4. Data Model

```mermaid
erDiagram
  AD_ITEM_CATALOG ||--o{ AD_ITEM_VARIANT_SCORE : "scored as"
  AD_ITEM_CATALOG ||--o{ AD_GROUP_ITEM : "part of"
  AD_GROUP_ITEM ||--o{ AD_GROUP_KEYWORD_V2 : "bids via"
  FEATURE_DEFINITION ||--o{ AD_ITEM_CATALOG : "defines features"

  AD_ITEM_CATALOG {
    string itemId PK
    string brand
    string categoryPathValue
    string productId
    string abstractProductId
    string primaryItemId
    string badgeType
    float reviewRating
    int reviewCount
  }

  AD_GROUP_KEYWORD_V2 {
    string keywordId PK
    string adGroupId
    float bid
    float troasBid
    string bidType
    string matchType
  }

  AD_ITEM_VARIANT_SCORE {
    string itemId PK
    string variantId PK
    float relevanceScore
    timestamp cachedAt
  }
```

---

## 5. Inter-Service Dependencies

```mermaid
graph TD
  midas_spade["midas-spade\n(HTTP/gRPC caller)"]
  midas_spector["midas-spector\n(gRPC SpBidsV2 caller)"]
  abram["abram"]
  solr_vespa[("Solr / Vespa\ncandidate retrieval")]
  sparkle["sparkle\n(relevance scoring)"]
  davinci["davinci\n(vector generation)"]
  buddy["sp-buddy\n(budget check)"]
  darpa["darpa\n(campaign metadata)"]
  kafka["Kafka\n(feature + request logs)"]
  azure_sql[("Azure SQL")]
  cassandra[("Cassandra\nscore cache")]

  midas_spade -->|"HTTP GET /v3/sp/search"| abram
  midas_spector -->|"gRPC SpBidsV2Rpc"| abram
  abram -->|"Darwin/AdGenie client"| solr_vespa
  abram -->|"POST /v3/scores (Protobuf)"| sparkle
  abram -->|"GET /v3/vector"| davinci
  abram -->|"GET budget status"| buddy
  abram -->|"GET campaign metadata\n(Bearer token)"| darpa
  abram -->|"Produce feature/request logs"| kafka
  abram -->|"Slick JDBC DAOs"| azure_sql
  abram -->|"Cassandra DAOs"| cassandra
```

---

## 6. Configuration

| Config Key | Description |
|-----------|-------------|
| `general.campaignServiceHost` | DARPA base URL |
| `general.campaign.service.authToken` | DARPA bearer token |
| `general.cassandra.*` | Cassandra credentials |
| `general.azsql.*` | Azure SQL credentials |
| `kafka.*` | Kafka brokers, SSL, topics |
| `kafkaGCPBrokers` | GCP Kafka for feature logging |
| `akka.http.parsing.max-uri-length` | `16k` — large query params |
| `server.tomcat.threads.max` | 200 (stage) / 300 (prod) |

---

## 7. Example Scenario — True Second Price Auction

```mermaid
sequenceDiagram
  participant SPADE as midas-spade
  participant ABRAM as abram /v3/sp/search
  participant DARWIN as Darwin/Solr (retrieval)
  participant SPARKLE as sparkle (scoring)
  participant DAVINCI as davinci (vectors)
  participant BUDDY as sp-buddy (budget)
  participant KAFKA as Kafka (logging)

  SPADE->>ABRAM: GET /v3/sp/search?query=headphones&userId=u789

  par Parallel retrieval
    ABRAM->>DARWIN: Retrieve candidates {query, categoryHint}
    DARWIN-->>ABRAM: [adGroupId, itemId, maxBid, ...]
  and ML enrichment
    ABRAM->>DAVINCI: GET /v3/vector {itemIds}
    DAVINCI-->>ABRAM: {embedding vectors}
  end

  ABRAM->>SPARKLE: POST /v3/scores {candidates + context}
  SPARKLE-->>ABRAM: {relevance scores per candidate}

  ABRAM->>BUDDY: GET /v2/budgets/campaigns/status
  BUDDY-->>ABRAM: {activeCampaignIds: [...]}

  Note over ABRAM: True Second Price Auction\nFor each ad slot:\n  rankedScore = relevanceScore × qualityScore × bid\n  winner = argmax(rankedScore)\n  cpc = 2nd_highest_score / winner.qualityScore

  ABRAM->>KAFKA: Produce feature log {query, winnerItemId, cpc, scores}
  ABRAM-->>SPADE: {ads: [winner1, winner2, ...], beacons: [...]}
```

---

## 8. ItemBadge Enum (Domain-specific)

```mermaid
graph LR
  ITEM_BADGE["ItemBadge"]
  ROLLBACK["ROLLBACK\n(Price reduced)"]
  REDUCED["REDUCED_PRICE"]
  CLEARANCE["CLEARANCE"]
  SPECIAL["SPECIAL_BUY"]
  LOW_STOCK["LOW_IN_STOCK"]
  NONE["NONE"]

  ITEM_BADGE --> ROLLBACK
  ITEM_BADGE --> REDUCED
  ITEM_BADGE --> CLEARANCE
  ITEM_BADGE --> SPECIAL
  ITEM_BADGE --> LOW_STOCK
  ITEM_BADGE --> NONE
```

---

## 9. Detailed Architecture

### 9.1 Four-Layer Component Model

```mermaid
graph TD
  subgraph "Layer 1 — Spring REST Controllers"
    L1_SP["SponsoredProductsController\n/v3/sp/search, /v3/sng/search"]
    L1_FS["FederatedSearchController\n/v3/fs/search"]
    L1_SB["SponsoredBrandsController\n/v3/sb/search"]
    L1_SV["SponsoredVideosController\n/v3/sv/search"]
    L1_SD["SponsoredDealsController\n/v3/sd/search"]
  end

  subgraph "Layer 2 — Service Orchestration"
    L2_RET["CandidateRetrievalService\n(Darwin + filtering)"]
    L2_ENR["FeatureEnrichmentService\n(Cassandra / Azure SQL)"]
    L2_BID["BiddingService\n(Spector + TROAS/PCVR)"]
    L2_RNK["RankingService\n(Sparkle ML scores)"]
    L2_DED["DeduplicationService\n(variant grouping)"]
  end

  subgraph "Layer 3 — External Service Clients"
    L3_DAR["AsyncDarwinClient\n→ Darwin"]
    L3_SPA["AsyncScoringServiceClient\n→ Sparkle"]
    L3_SPE["AsyncSpectorClient\n→ Spector"]
    L3_CAM["CampaignServiceClient\n→ DARPA"]
  end

  subgraph "Layer 4 — Data Access (DAOs)"
    L4_C1["AdItemCatalogDao\n(Cassandra)"]
    L4_C2["TaxonomyVectorsDao\n(Cassandra)"]
    L4_S1["ProductTypeDao\n(Azure SQL)"]
    L4_S2["VectorServingModelDao\n(Azure SQL)"]
  end

  L1_SP --> L2_RET
  L1_SP --> L2_ENR
  L1_SP --> L2_BID
  L1_SP --> L2_RNK
  L1_SP --> L2_DED
  L1_FS --> L2_RET
  L1_SB --> L2_RET
  L1_SV --> L2_RET
  L1_SD --> L2_RET

  L2_RET --> L3_DAR
  L2_BID --> L3_SPE
  L2_RNK --> L3_SPA
  L2_ENR --> L3_CAM

  L2_ENR --> L4_C1
  L2_ENR --> L4_C2
  L2_ENR --> L4_S1
  L2_ENR --> L4_S2
```

### 9.2 Multi-Surface API Endpoints

| Surface | Endpoint | Controller | Key Features |
|---------|----------|-----------|--------------|
| Sponsored Products | `/v3/sp/search`, `/v3/sng/search` | `SponsoredProductsController` | Item-level targeting, Sparkle ranking, TROAS/PCVR bidding, position-specific optimization |
| Federated Search | `/v3/fs/search` | `FederatedSearchController` | Multi-source aggregation, cross-vertical ad serving, deduplication |
| Sponsored Brands | `/v3/sb/search` | `SponsoredBrandsController` | Brand-level targeting, Brand Amplification logic, SBA auction |
| Sponsored Videos | `/v3/sv/search` | `SponsoredVideosController` | Video-specific eligibility, product type filtering, content validation |
| Sponsored Deals | `/v3/sd/search` | `SponsoredDealsController` | Deal eligibility, promotion-based filtering, time-sensitive handling |

**Administrative Endpoints:**
- `GET /` — Service root ("w00t")
- `GET /v3/healthcheck` — Detailed health check (K8s readiness probe)
- `GET /v3/live` — Liveness probe
- `GET /admin/app-config` — Application configuration
- `GET /admin/ccm-config` — CCM configuration

### 9.3 8-Step Request Processing Pipeline

```mermaid
flowchart TD
  S1["STEP 1: Request Parsing\n• Extract params from HttpServletRequest\n• Validate required fields (query, tenant, placement)\n• Generate unique request UUID\n• Determine debug mode"]
  S2["STEP 2: Retrieval Strategy Selection\n• Check darwin.enabled flag\n• Determine Darwin sources (Solr, Vespa, Polaris)\n• Load surface-specific configuration"]
  S3["STEP 3: Call Darwin for Ad Retrieval\n(timeout: 1000ms)\n• Send: request ID, query, sources, filters, useDavinci flag\n• Receive: DarwinResponse protobuf with filtered ad items\n• Client: AsyncDarwinClient.retrieveAds()"]
  S4["STEP 4: Feature Enrichment\n• Fetch product metadata from Cassandra\n• Load product type hierarchy from Azure SQL\n• Add taxonomy vectors\n• Fetch promotion rules from SPTools"]
  S5["STEP 5: Bid Retrieval & Calculation\n(timeout: 150ms)\n• Call Spector for base static bids\n• Apply dynamic bidding (TROAS, PCVR, PVPC)\n• Apply pacing factors\n• Enforce floor bids and caps"]
  S6["STEP 6: ML Scoring & Ranking\n(timeout: 100ms)\n• Call Sparkle for ML scores\n• FinalScore = Bid × RelevanceScore\n• Sort candidates by final score"]
  S7["STEP 7: Deduplication\n• Remove duplicate campaigns and products\n• Apply variant grouping"]
  S8["STEP 8: Response Construction\n• Select top-N ads\n• Format response as JSON\n• Add tracking metadata"]

  S1 --> S2 --> S3 --> S4 --> S5 --> S6 --> S7 --> S8
```

### 9.4 External Service Dependencies (Detailed)

| Service | Role | App Key / Base URL | Client Class | Timeout | Notes |
|---------|------|--------------------|--------------|---------|-------|
| **Darwin** | Ad retrieval & filtering | App Key: `SP-DARWIN-WMT` | `AsyncDarwinClient` | 1000ms | Sends sources (Solr, Vespa, Polaris), receives `DarwinResponse` protobuf. AbRAM does NOT call DaVinci directly — Darwin calls DaVinci when `useDavinci=true` |
| **Sparkle** | ML-based relevance scoring | `http://sparkle-wmt.prod.walmart.com` | `AsyncScoringServiceClient` | 1000ms | Endpoint `/v1/scores`; called after bid retrieval. Ranking: `FinalScore = Bid × SparkleScore`. Supports batch scoring |
| **Spector** | Bid retrieval & dynamic bidding | `spector-wmt-v2.prod.walmart.com` | `AsyncSpectorClient` | 150ms | Steps: (1) retrieve base bids, (2) apply TROAS, (3) apply PCVR/PVPC, (4) enforce floor bids, (5) apply pacing |
| **DARPA** (Campaign Service) | Campaign metadata & eligibility | configured via `general.campaignServiceHost` | `CampaignServiceClient` | — | Bearer token auth; provides campaign metadata |
| **SPTools** | Promotion rules & ad badges | — | — | — | Fetched during feature enrichment step |
| **DaVinci** | Relevance scoring (vectors) | `GET /v3/vector` | (called via Darwin) | — | AbRAM may call directly for vector enrichment; Darwin also calls DaVinci internally |
| **sp-buddy** | Budget check | — | — | — | `GET /v2/budgets/campaigns/status` |

### 9.5 Data Stores

**Cassandra Tables**

| Table | Purpose | TTL |
|-------|---------|-----|
| `ads_item_store` | Ad item catalog: product metadata, ratings, attributes | 3 days |
| `anchor_item_info_v3` | Anchor item relationships for complementary products | — |
| `table_sba_signals` | Sponsored Brand Amplifier signals | — |
| `taxonomy_vectors` | Category taxonomy embeddings | — |
| `AdItemVariantScoreCache` | Variant relevance score cache (from § 4 data model) | — |

**Azure SQL Database: `sp-ad-serving`**

| Category | Tables / Purpose |
|----------|-----------------|
| Product Type | Hierarchy and mappings (`ProductTypeDao`) |
| Taxonomy | Category relationships |
| Vector Serving Models | ML model metadata (`VectorServingModelDao`) |
| Ad Item Catalog | Item metadata, keywords, feature definitions (accessed via Slick JDBC) |

**Azure Blob Storage**

| Container | Contents |
|-----------|----------|
| `midas-trained-models` | ML models (MLeap format) |
| `sp-troas-config` | TROAS placement configuration |
| `sp-db-config` | Dynamic bidding configuration |

### 9.6 Performance Characteristics

| Component | Target Latency | Max Latency |
|-----------|---------------|-------------|
| End-to-end (p99) | < 500ms | < 1000ms |
| Darwin retrieval | < 250ms | 1000ms |
| Sparkle scoring | < 100ms | 1000ms |
| Spector bidding | < 150ms | 300ms |

Throughput: 1000s of requests/second per instance; 500+ concurrent connections per retriever.

### 9.7 Configuration Layers

Configuration is applied in the following precedence order (lowest to highest):

1. **Application Defaults** — `common.defaults.conf`
2. **Environment-Specific (CCM)** — `non-prod-1.0-ccm.yml`, `prod-1.0-ccm.yml`
3. **Surface-Specific** — `properties/` directory
4. **Runtime Overrides** — System Properties, request parameters

Key CCM properties:

```
darwin.enabled=true
darwin.timeout=1000
sparkle.base.url=http://sparkle-wmt.prod.walmart.com
sparkle.timeout=1000
spector.host=http://spector-wmt-v2.qa.walmart.com
spector.V4.enabled=true
variantBidding.enabled=true
troas.enabled=true
```

Full CCM path (prod): `ccm/wmt/prod-1.0-ccm.yml`

### 9.8 Deployment

| Attribute | Value |
|-----------|-------|
| WCNP Namespace | `sp-abram-wmt` |
| Regions | SCUS, EUS2, WUS2 |
| Repo | `gecgithub01.walmart.com/labs-ads/abram` |
| CCM (prod) | `ccm/wmt/prod-1.0-ccm.yml` |
| K8s readiness probe | `GET /v3/healthcheck` |
| K8s liveness probe | `GET /v3/live` |
