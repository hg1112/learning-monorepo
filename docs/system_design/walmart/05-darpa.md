# Chapter 5 — darpa (Campaign Management Service)

## 1. Overview

**darpa** (Dashboard for Ads Revenue & Performance Analysis) is the primary campaign management API for Walmart Sponsored Products. Advertisers use it to create and manage campaigns, ad groups, keywords, bids, media, and brand assets. It also drives reporting, analytics, and async snapshot generation.

- **Domain:** Campaign Lifecycle Management & Reporting
- **Tech:** Java + Scala 2.12, Play Framework 2.2, Akka, Hibernate, SBT
- **WCNP Namespace:** `midas-darpa`
- **Port:** 8080

---

## 2. Architecture Diagram

```mermaid
graph LR
  subgraph darpa
    ROUTES["Play Routes\n(v1api.routes — 121 controllers)"]
    CAMPAIGN_CTRL["CampaignApiController"]
    ADGROUP_CTRL["AdGroupApiController"]
    KEYWORD_CTRL["KeywordApiController"]
    REPORT_CTRL["ReportApiController"]
    SNAPSHOT_CTRL["SnapshotApiController"]
    MEDIA_CTRL["MediaApiController"]
    BRAND_CTRL["BrandAssetApiController"]
    SBA_CTRL["SbaProfileController"]

    DB_ORACLE[("Oracle DB\n(primary OLTP)")]
    DB_SQL["Azure SQL\n(readwrite + jobs)"]
    DB_REPORTING["Azure SQL\n(reporting)"]

    ROUTES --> CAMPAIGN_CTRL
    ROUTES --> ADGROUP_CTRL
    ROUTES --> KEYWORD_CTRL
    ROUTES --> REPORT_CTRL
    ROUTES --> SNAPSHOT_CTRL

    CAMPAIGN_CTRL --> DB_ORACLE
    ADGROUP_CTRL --> DB_ORACLE
    KEYWORD_CTRL --> DB_ORACLE
    REPORT_CTRL --> DB_REPORTING
    SNAPSHOT_CTRL --> DB_SQL
  end

  KAFKA[("Kafka\n(async events)")]
  IRO["IRO Item Read API"]
  WPA["WPA Audit API"]
  AZURE_STORAGE["Azure Blob Storage\n(media uploads)"]

  darpa -->|"Produce campaign events"| KAFKA
  darpa -->|"Item search & attributes"| IRO
  darpa -->|"Audit trail"| WPA
  darpa -->|"Upload media/brand assets"| AZURE_STORAGE
```

---

## 3. API / Interface

| Method | Path | Description |
|--------|------|-------------|
| POST | `/srv/api/v1/login` | Advertiser authentication |
| POST | `/srv/api/v1/campaigns` | Create campaign |
| GET | `/srv/api/v1/campaigns` | List campaigns |
| PUT | `/srv/api/v1/campaigns` | Update campaign |
| PUT | `/srv/api/v1/campaigns/delete` | Delete campaign |
| POST | `/srv/api/v1/adGroups` | Create ad group |
| GET | `/srv/api/v1/adGroups` | List ad groups |
| POST | `/srv/api/v1/keywords` | Create keywords |
| GET | `/srv/api/v1/keywords` | List keywords |
| GET | `/srv/api/v1/keyword_suggestions` | Keyword suggestions |
| POST | `/srv/api/v1/adItems` | Create ad items |
| GET | `/srv/api/v1/adItems` | List ad items |
| GET | `/srv/api/v1/reports/by_day` | Daily performance report |
| GET | `/srv/api/v1/reports/by_adgroup` | Ad group report |
| GET | `/srv/api/v1/reports/by_item` | Item-level report |
| POST | `/srv/api/v1/snapshot/report` | Async report snapshot |
| GET | `/srv/api/v1/snapshot` | Get snapshot results |
| POST | `/srv/api/v1/media/upload` | Upload creative media |
| POST | `/srv/api/v1/brand_assets` | Create brand asset |
| POST | `/srv/api/v1/sba_profile` | Create Search Brand Amplifier profile |
| POST/GET/PUT | `/srv/api/v1/multipliers/placement` | Placement bid multipliers |

---

## 4. Data Model

```mermaid
erDiagram
  ADVERTISER ||--o{ USER_ACCESS : "grants"
  ADVERTISER ||--o{ CAMPAIGN : "owns"
  CAMPAIGN ||--o{ AD_GROUP : "contains"
  AD_GROUP ||--o{ AD_ITEM : "targets"
  AD_GROUP ||--o{ KEYWORD : "bids on"
  AD_GROUP ||--o{ BRAND_ASSET : "uses"
  CAMPAIGN ||--o{ PLACEMENT_MULTIPLIER : "has"
  CAMPAIGN ||--o{ PLATFORM_MULTIPLIER : "has"
  CAMPAIGN ||--o{ CAMPAIGN_METRIC : "reported by"

  CAMPAIGN {
    string campaignId PK
    string advertiserId
    string name
    string type
    string status
    float dailyBudget
    float totalBudget
    date startDate
    date endDate
    string tenantId
  }

  AD_GROUP {
    string adGroupId PK
    string campaignId FK
    string name
    float bid
    string targetingType
    string status
  }

  KEYWORD {
    string keywordId PK
    string adGroupId FK
    string keyword
    string matchType
    float bid
    string status
  }
```

---

## 5. Inter-Service Dependencies

```mermaid
graph TD
  darpa["darpa"]
  radpub["radpub-v2\n(Kafka consumer)"]
  sp_crs["sp-crs\n(CampaignServerClient caller)"]
  sp_buddy["sp-buddy\n(DarpaService caller)"]
  marty["marty / marty-fe\n(HTTP caller)"]
  kafka["Kafka\n(ad-item-topic)"]
  iro["IRO Item Read API"]
  wpa["WPA Audit API"]
  azure_blob["Azure Blob Storage"]
  oracle[("Oracle DB")]
  azure_sql[("Azure SQL\nreporting + jobs")]

  marty -->|"POST /srv/api/v1/campaigns"| darpa
  sp_crs -->|"GET demandMetadata\nPOST populateBudgetTable"| darpa
  sp_buddy -->|"GET liveCampaigns\nPUT budgetStatus"| darpa
  darpa -->|"Publish campaign updates"| kafka
  kafka -->|"Consume ad-item-topic"| radpub
  darpa -->|"Item search"| iro
  darpa -->|"Audit log"| wpa
  darpa -->|"Media storage"| azure_blob
  darpa -->|"Campaign OLTP"| oracle
  darpa -->|"Reports + jobs"| azure_sql
```

---

## 6. Configuration

| Config Key | Description |
|-----------|-------------|
| `runOnEnv` | Runtime environment (dev, prod) |
| `apiEndPoint` | Self-referential API endpoint |
| `normalize-query-context.parallelism` | Query normalization threads (4) |
| `kafka-context` | Kafka dispatcher configuration |
| `azure-job-context` | Background job thread context |
| `bigquery-job-context` | BigQuery async jobs |
| `graphite.app.enableMetrics` | Enable Graphite metrics (false) |

---

## 7. Example Scenario — Advertiser Creates a Sponsored Product Campaign

```mermaid
sequenceDiagram
  actor Advertiser
  participant DARPA as darpa
  participant ORACLE as Oracle DB
  participant KAFKA as Kafka (ad-item-topic)
  participant RADPUB as radpub-v2
  participant SOLR as Solr/Vespa

  Advertiser->>DARPA: POST /srv/api/v1/login {credentials}
  DARPA-->>Advertiser: 200 {sessionToken}

  Advertiser->>DARPA: POST /srv/api/v1/campaigns\n{name, budget, startDate}
  DARPA->>ORACLE: INSERT INTO CAMPAIGN
  DARPA-->>Advertiser: 201 {campaignId: "camp-456"}

  Advertiser->>DARPA: POST /srv/api/v1/adGroups\n{campaignId, bid: 0.75, targetingType: AUTO}
  DARPA->>ORACLE: INSERT INTO AD_GROUP
  DARPA-->>Advertiser: 201 {adGroupId: "ag-789"}

  Advertiser->>DARPA: POST /srv/api/v1/adItems\n{adGroupId, itemId: "prod-999"}
  DARPA->>ORACLE: INSERT INTO AD_ITEM
  DARPA->>KAFKA: Publish {adGroupId, itemId, bid, tenant}
  DARPA-->>Advertiser: 201 {adItemId: "ai-101"}

  KAFKA-->>RADPUB: Consume ad-item-topic message
  RADPUB->>SOLR: Index {adGroupId, itemId, bid, brand, categoryPath}
  Note over SOLR: Ad is now searchable — available for serving
```
