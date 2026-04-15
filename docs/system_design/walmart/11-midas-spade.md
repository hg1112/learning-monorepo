# Chapter 11 — midas-spade (Ad Server)

## 1. Overview

**midas-spade** is the primary **ad serving API** for Walmart Sponsored Products. It receives real-time ad requests from Walmart's search platform (query + context), fans out to the bidding and ranking pipeline, and returns a ranked list of sponsored ads to display. It acts as the orchestrator for the entire ad serving pipeline.

- **Domain:** Ad Serving / Real-Time Bidding Orchestration
- **Tech:** Java 17, Spring Boot 3.5.6, AsyncHttpClient, multi-module Maven (app, common, dao, abtest)
- **WCNP Namespace:** `midas-spade-app`
- **Port:** 8080
- **Swagger:** `https://midas-spade.dev.walmart.com/docs`

---

## 2. Architecture Diagram

```mermaid
graph LR
  subgraph midas-spade
    SP_CTRL["SponsoredProductsController\nGET/POST /v1/sp/getAds"]
    SB_CTRL["SponsoredBrandsController\nGET /v1/sb/getAds"]
    SV_CTRL["SponsoredVideoAdsController\nGET/POST /v1/sv/getAds"]
    FS_CTRL["FederatedSearchController\nGET/POST /v2/fs/getAds"]
    TPA_CTRL["SecondPriceAuctionController\nPOST /v2/sp/2pa"]
    ADMIN_CTRL["AdminController\n/admin/*"]

    ABRAM_CLIENT["AsyncAbramClient\n(async HTTP)"]
    PERCEIVE_CLIENT["AsyncPerceiveClient\n(Query Understanding)"]
    WEBSTER_CLIENT["AsyncWebsterClient\n(Query Understanding)"]
    DAVINCI_CLIENT["AsyncDavinciClient\n(GenAI/ML)"]
    SI_ACE_CLIENT["SearchIncrementalityAceClient\n(A/B testing)"]

    SP_CTRL --> ABRAM_CLIENT
    SP_CTRL --> PERCEIVE_CLIENT
    SP_CTRL --> DAVINCI_CLIENT
    SB_CTRL --> ABRAM_CLIENT
    FS_CTRL --> ABRAM_CLIENT
    FS_CTRL --> PERCEIVE_CLIENT
    FS_CTRL --> WEBSTER_CLIENT
    TPA_CTRL --> ABRAM_CLIENT
    SP_CTRL --> SI_ACE_CLIENT
  end

  ABRAM["abram\n(Auction Engine)"]
  SPECTOR["midas-spector\n(Sharded Bidding)"]
  PERCEIVE["Perceive\n(Query Understanding)"]
  WEBSTER["Webster\n(Query Understanding)"]
  DAVINCI["davinci\n(ML Platform)"]
  SI_ACE["sp-ace\n(SI Experiments)"]
  SQL[("Azure SQL\nAd placements")]
  GEO["Geo-Sourcing Service"]

  ABRAM_CLIENT -->|"HTTP async (Protobuf)"| ABRAM
  ABRAM_CLIENT -->|"HTTP async (Protobuf)"| SPECTOR
  PERCEIVE_CLIENT -->|"HTTP async"| PERCEIVE
  WEBSTER_CLIENT -->|"HTTP async"| WEBSTER
  DAVINCI_CLIENT -->|"HTTP async"| DAVINCI
  SI_ACE_CLIENT -->|"HTTP async"| SI_ACE
```

---

## 3. API / Interface

| Method | Path | Description |
|--------|------|-------------|
| GET/POST | `/v1/sp/getAds` | Sponsored Products ad retrieval |
| GET | `/v1/sb/getAds` | Sponsored Brands ad retrieval |
| GET/POST | `/v1/sv/getAds` | Sponsored Video ad retrieval |
| GET/POST | `/v1/fs/getAds` | Federated Search ads (v1) |
| GET/POST | `/v2/fs/getAds` | Federated Search ads (v2) |
| POST | `/v2/sp/2pa` | True Second Price Auction |
| GET | `/v1/fg/getAds` | Fungibility ads |
| GET | `/v1/healthcheck` | Health check |
| GET | `/admin/app-config` | All CCM configs |
| GET | `/admin/app-config/{module}` | Module CCM config |
| GET | `/admin/caches/evict` | Evict cache entry |
| GET | `/admin/caches/bulkEvict` | Bulk evict cache entries |
| GET | `/admin/caches/cache` | Get cache value |

**Request parameters:** `query`, `pageContext`, `userId`, `placementContext`, `tenant`, `moduleLocation`

---

## 4. Data Model

```mermaid
erDiagram
  AD_PLACEMENT ||--o{ AD_PLACEMENT_ROW : "stored as"
  PRODUCT_TYPE ||--o{ PRODUCT_TYPE_ROW : "stored as"
  ADS_REQUEST ||--o{ AD_PLACEMENT : "maps to"
  ADS_REQUEST ||--o{ AB_TEST_BUCKET : "assigned to"

  ADS_REQUEST {
    string query
    string pageType
    string moduleLocation
    string tenant
    string userId
    string sessionId
  }

  AD_PLACEMENT {
    string tenant
    string api
    string pageType
    string moduleLocation
    string beaconModuleLocation
    boolean isActive
  }

  AB_TEST_BUCKET {
    string bucketId
    string layerId
    string experimentId
    string assignedValue
  }
```

---

## 5. Inter-Service Dependencies

```mermaid
graph TD
  search["Walmart Search\n(caller)"]
  midas_spade["midas-spade"]
  abram["abram\n(AsyncAbramClient)"]
  spector["midas-spector\n(AsyncAbramClient)"]
  perceive["Perceive\n(Query Understanding)"]
  webster["Webster\n(Query Understanding)"]
  davinci["davinci\n(GenAI ML vectors)"]
  si_ace["sp-ace\n(SI Experiments)"]
  iro["IRO Item API\n(item attributes)"]
  geo["Geo-Sourcing Service"]
  sql[("Azure SQL\nplacements")]

  search -->|"GET /v1/sp/getAds"| midas_spade
  midas_spade -->|"async HTTP Protobuf"| abram
  midas_spade -->|"async HTTP Protobuf"| spector
  midas_spade -->|"async HTTP"| perceive
  midas_spade -->|"async HTTP"| webster
  midas_spade -->|"async HTTP"| davinci
  midas_spade -->|"async HTTP"| si_ace
  midas_spade -->|"async HTTP"| iro
  midas_spade -->|"HTTP"| geo
  midas_spade -->|"JDBC AdPlacementDao"| sql
```

---

## 6. Configuration

| Config Key | Description |
|-----------|-------------|
| `isTest.allow` | Allow test endpoints |
| `spring.mvc.async.request-timeout` | 100000ms async timeout |
| `server.max-http-request-header-size` | 64KB max header |
| `appConfig.getPerceiveConnectionsPerHost()` | Perceive connection pool size |
| `appConfig.getWebsterConnectionsPerHost()` | Webster connection pool size |
| `appConfig.getSIAceMaxConnections()` | SI ACE max connections |
| `ccm.enabled` | CCM feature toggle |
| `spring.profiles.active` | `local`, `stg`, `prod`, `wcnp_*` |

---

## 7. Example Scenario — Sponsored Product getAds Request

```mermaid
sequenceDiagram
  participant SEARCH as Walmart Search
  participant SPADE as midas-spade /v1/sp/getAds
  participant PERCEIVE as Perceive (Query Understanding)
  participant SPECTOR as midas-spector /v8/sp/ads
  participant ABRAM as abram /v3/sp/search
  participant SPARKLE as sparkle /v3/scores
  participant DAVINCI as davinci /v3/vector
  participant SOLR as Solr/Vespa

  SEARCH->>SPADE: GET /v1/sp/getAds?query=laptop&userId=u123&tenant=WMT
  Note over SPADE: Parallel async fan-out

  par Query Understanding
    SPADE->>PERCEIVE: async GET queryUnderstanding?query=laptop
    PERCEIVE-->>SPADE: {normalizedQuery, categoryHint}
  and Bid retrieval
    SPADE->>SPECTOR: async POST /v8/sp/ads {query, context}
    SPECTOR-->>SPADE: {bids: [...]}
  and GenAI enrichment
    SPADE->>ABRAM: async GET /v3/sp/search {query}
    ABRAM->>SOLR: Retrieve ad candidates by query
    SOLR-->>ABRAM: [adGroupId, itemId, bid, ...]
    ABRAM->>SPARKLE: POST /v3/scores {candidates}
    SPARKLE-->>ABRAM: {scores: [...]}
    ABRAM->>DAVINCI: GET /v3/vector {itemIds}
    DAVINCI-->>ABRAM: {vectors: [...]}
    ABRAM-->>SPADE: {rankedAds: [...]}
  end

  SPADE->>SPADE: Merge results + apply placement rules
  SPADE-->>SEARCH: 200 {ads: [SponsoredProductAd, ...], beacons: [...]}
```
