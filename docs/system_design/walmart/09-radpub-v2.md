# Chapter 9 — radpub-v2 (Campaign Indexer / Publisher)

## 1. Overview

**radpub-v2** (Realtime Ad Publishing) is a Kafka-consumer service that bridges campaign management (DARPA) and ad retrieval (Solr/Vespa). When a campaign or ad item is created/updated in DARPA, an event is published to Kafka. radpub-v2 consumes these events, enriches them with item catalog data (IRO), and indexes the ad candidates into **Solr** and **Vespa** for millisecond-latency retrieval during ad serving.

- **Domain:** Ad Indexing & Publishing Pipeline
- **Tech:** Java 17, Spring Boot 3.5.6, Spring Cloud Stream (Kafka binder)
- **WCNP Namespaces:** `radpub-wmt`, `radpub-sams`, `radpub-intl`
- **Tenants:** WMT, SAMS, INTL
- **Port:** 8080 (HTTP health checks only — no REST API)

---

## 2. Architecture Diagram

```mermaid
graph LR
  subgraph radpub-v2
    KAFKA_CONSUMER["CampaignStream\n(@Bean processCampaignMessage)\nSpring Cloud Stream"]
    STANDARD["CampaignMessageConsumerImpl\n(standard processing)"]
    SOX["CampaignMessageConsumerSoxImpl\n(SOX compliance + Hawkshaw)"]
    IRO_SVC["IroService\n(item enrichment)"]
    DARPA_SVC["DarpaServiceImpl\n(ad group sync)"]
    SOLR_IDX["SolrIndexService\n(write to Solr)"]
    VESPA_IDX["VespaIndexService\n(write to Vespa)"]
    CASSANDRA_SVC["CassandraService\n(eligibility cache)"]

    KAFKA_CONSUMER --> STANDARD
    KAFKA_CONSUMER --> SOX
    STANDARD --> IRO_SVC
    STANDARD --> DARPA_SVC
    STANDARD --> SOLR_IDX
    STANDARD --> VESPA_IDX
    STANDARD --> CASSANDRA_SVC
    SOX -->|"Hawkshaw signal"| HAWKSHAW
  end

  KAFKA[("Kafka\nad-item-topic")]
  IRO["IRO Item Read API"]
  DARPA["darpa\n(ad group sync)"]
  SOLR[("Solr\nsponsoredProducts\nsponsoredProductsKWB")]
  VESPA[("Vespa\n(search + ranking)")]
  CASSANDRA[("Cassandra\nsp_ad_eligibility")]
  AZURE_SQL[("Azure SQL")]
  HAWKSHAW["Hawkshaw\n(SOX audit)"]

  KAFKA -->|"Consume ad-item-topic"| KAFKA_CONSUMER
  IRO_SVC -->|"HTTP item attributes"| IRO
  DARPA_SVC -->|"HTTP ad group sync"| DARPA
  SOLR_IDX -->|"Solr document write"| SOLR
  VESPA_IDX -->|"Vespa document write"| VESPA
  CASSANDRA_SVC -->|"Write eligibility"| CASSANDRA
```

---

## 3. API / Interface

**radpub-v2 has no REST API endpoints** — it is a pure event consumer. It only exposes:

| Endpoint | Description |
|----------|-------------|
| Spring Boot Actuator (`/actuator/health`) | Kubernetes liveness/readiness |
| Prometheus metrics (`/actuator/prometheus`) | Metrics scraping |

**Kafka Consumer Binding:**
- **Input binding:** `processCampaignMessage-in-0`
- **Topic:** Configured via `ccm.kafka.ad-item-topic`
- **Consumer group:** Configured via `kafka.consumer-group`
- **DLQ (Dead Letter Queue):** Configurable via `ccm.kafka.enableDlq`

---

## 4. Data Model

```mermaid
erDiagram
  KAFKA_MESSAGE ||--o{ AD_ITEM_SOLR_DOC : "produces"
  KAFKA_MESSAGE ||--o{ CASSANDRA_ELIGIBILITY : "writes"

  AD_ITEM_SOLR_DOC {
    string tenant
    string itemId
    string adGroupId
    string campaignId
    string name
    string shortDescription
    string brand
    string departmentId
    string categoryId
    string productFamilyId
    boolean nextDayEligible
    timestamp lastModified
  }

  CASSANDRA_ELIGIBILITY {
    string itemId PK
    string tenant PK
    boolean isEligible
    timestamp updatedAt
  }

  TROAS_CAMPAIGN_CACHE {
    string campaignId PK
    float troasGoal
    string status
    timestamp cachedAt
  }
```

---

## 5. Inter-Service Dependencies

```mermaid
graph TD
  kafka["Kafka\n(ad-item-topic)"]
  radpub["radpub-v2"]
  iro["IRO Item Read API\n(item enrichment)"]
  darpa["darpa\n(ad group sync)"]
  solr[("Solr\nsponsored products collections")]
  vespa[("Vespa\nranking engine")]
  cassandra[("Cassandra\nsp_ad_eligibility")]
  azure_sql[("Azure SQL")]

  kafka -->|"Spring Cloud Stream consumer"| radpub
  radpub -->|"HTTP CloseableHttpClient\nitem attributes"| iro
  radpub -->|"HTTP CloseableHttpClient\nad group sync"| darpa
  radpub -->|"SolrClient write"| solr
  radpub -->|"Vespa document API"| vespa
  radpub -->|"Cassandra CQL"| cassandra
  radpub -->|"JDBC"| azure_sql
```

---

## 6. Configuration

| Profile | Config File | Description |
|---------|-------------|-------------|
| `localWmt` | `application-localWmt.yml` | Local WMT development |
| `localSams` | `application-localSams.yml` | Local SAMS development |
| `Wmt` | `application-Wmt.yml` | Production WMT |
| `Sams` | `application-Sams.yml` | Production SAMS |
| `Intl` | `application-Intl.yml` | International production |

| Config Key | Description |
|-----------|-------------|
| `ccm.kafka.brokers` | Kafka broker addresses |
| `ccm.kafka.ad-item-topic` | Input topic name |
| `kafka.consumer-group` | Consumer group ID |
| `ccm.kafka.enableDlq` | Enable dead-letter queue |
| `ccm.solr.zookeepers` | Solr ZooKeeper ensemble |
| `ccm.sox.enabled` | Enable SOX compliance (Hawkshaw) |
| `ccm.teflon` | Enable Teflon mode |
| `runtime.context.appName` | App name per tenant |

---

## 7. Example Scenario — Ad Item Published to Solr

```mermaid
sequenceDiagram
  participant DARPA as darpa
  participant KAFKA as Kafka (ad-item-topic)
  participant RADPUB as radpub-v2
  participant IRO as IRO Item API
  participant DARPA2 as darpa (adGroup sync)
  participant SOLR as Solr
  participant VESPA as Vespa
  participant CASSANDRA as Cassandra

  DARPA->>KAFKA: Produce {adGroupId, itemId, bid, campaignId, tenant}

  KAFKA-->>RADPUB: Consume message (processCampaignMessage)
  Note over RADPUB: Validate + enrich message

  RADPUB->>IRO: GET item attributes {itemId}\n(name, brand, category, shortDesc)
  IRO-->>RADPUB: {name, brand, categoryPath, nextDayEligible}

  RADPUB->>DARPA2: HTTP ad group sync {adGroupId}
  DARPA2-->>RADPUB: {adGroupStatus, bidMultipliers}

  RADPUB->>SOLR: POST /solr/sponsoredProducts/update\n{AdItemSolrDocument}
  SOLR-->>RADPUB: 200 OK

  RADPUB->>VESPA: PUT /document/v1/{itemId}\n{bid, brand, category, eligible}
  VESPA-->>RADPUB: 200 OK

  RADPUB->>CASSANDRA: INSERT sp_ad_eligibility\n{itemId, tenant, isEligible=true}
  Note over SOLR,VESPA: Ad candidate is now searchable for retrieval
```
