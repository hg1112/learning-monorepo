# High-Level Design — Sponsored Products Advertising Platform

## Executive Summary

This platform is a **full-stack Walmart Sponsored Products advertising system** enabling advertisers to create, manage, and optimize campaigns across Walmart.com, Sam's Club, and International properties. It spans 19 repos across 6 functional domains:

1. **Advertiser Tooling** — `darpa`, `sp-ace`, `marty`, `marty-fe`
2. **Ad Indexing** — `radpub-v2`
3. **Ad Serving Core** — `midas-spade`, `midas-spector`, `abram`
4. **ML Scoring** — `sparkle`, `davinci`
5. **ML Infrastructure** — `element-davinci`, `element-ss-inference`, `sp-adserver-feast`, `element-adserver-feast`
6. **Click & Budget** — `sp-crs`, `sp-buddy`

**Platform lifecycle (11 steps):**
1. Advertiser creates campaign (darpa / marty)
2. A/B experiment assigned (sp-ace → sp-buddy)
3. Campaign published to Kafka (darpa → campaign-events)
4. Ad indexed into Solr/Vespa (radpub-v2)
5. Shopper search triggers ad serving (midas-spade)
6. Bids computed (midas-spector, abram)
7. Candidates scored for relevance (sparkle, davinci)
8. ML inference on H100 GPUs (element-davinci via davinci)
9. Features fetched from Feast/Cassandra (sp-adserver-feast)
10. TSP auction selects winners (abram)
11. Shopper clicks → dedup → budget deducted (sp-crs → sp-buddy)

---

## C4 System Context Diagram

```mermaid
graph TB
    advertiser["Advertiser\nCreates and manages ad campaigns via API or AI agent"]
    shopper["Shopper\nBrowses Walmart.com / Sam's Club, sees sponsored ads"]
    ops["Ops / DS Team\nMonitors, experiments, deploys ML models"]

    subgraph sp_boundary["Walmart SP Advertising Platform"]
        sp_platform["SP Advertising Platform\nEnd-to-end sponsored product ad lifecycle:\ncreation, indexing, serving, ML scoring, budget pacing"]
    end

    walmart_search["Walmart Search API\nProvides item/product catalog data"]
    iro["IRO (Item Record Object)\nProduct enrichment service"]
    azure_sql["Azure SQL / Oracle\nRelational campaign storage"]
    cassandra["Cassandra Clusters\nLow-latency feature + dedup store"]
    solr_vespa["Solr / Vespa\nAd candidate indexing and retrieval"]
    kafka["Kafka (SOX + GCP)\nEvent streaming backbone"]
    bigquery["Google BigQuery\nBatch feature source, analytics"]
    azure_blob["Azure Blob Store\nML model weight storage"]
    llm_gw["Element LLM Gateway\nAzure OpenAI (o3-mini) access"]
    akeyless["Akeyless\nSecrets vault"]

    advertiser -->|"Creates campaigns, queries performance"| sp_platform
    shopper -->|"Triggers ad serving via search"| sp_platform
    ops -->|"Deploys models, manages experiments"| sp_platform
    sp_platform -->|"Item catalog lookups"| walmart_search
    sp_platform -->|"Product enrichment"| iro
    sp_platform -->|"Campaign + budget data"| azure_sql
    sp_platform -->|"Features, dedup, cache"| cassandra
    sp_platform -->|"Ad candidate index"| solr_vespa
    sp_platform -->|"Events: campaigns, clicks, budgets"| kafka
    sp_platform -->|"Batch feature computation"| bigquery
    sp_platform -->|"Model weight loading"| azure_blob
    sp_platform -->|"LLM inference for AI agent"| llm_gw
    sp_platform -->|"Secrets retrieval"| akeyless
```

---

## Full Service Map

```mermaid
graph TB
    subgraph Advertisers["🏢 Advertiser Plane"]
        MARTY_FE["**marty-fe**\nPython 3.11 · FastAPI · LangGraph\nAdvertiser-facing AI agent clone"]
        MARTY["**marty**\nPython 3.11 · FastAPI · LangGraph 0.5\nAI agent: IdMapper→Router→Argo/Walsa→LLM"]
        DARPA["**darpa**\nScala 2.12 · Play 2.7 · Akka · Hibernate\nCampaign CRUD · 121 routes · Oracle+Azure SQL"]
        ACE["**sp-ace**\nJava 17 · Spring Boot 3.5.7 · EhCache\nA/B experiments · Experiment state machine"]
    end

    subgraph Indexing["🗂️ Indexing Pipeline"]
        RADPUB["**radpub-v2**\nJava 17 · Spring Boot 3.5.6 · Cloud Stream\nKafka consumer → IRO enrich → Solr/Vespa/Cassandra"]
        SOLR_VESPA[("Solr / Vespa\nAd Candidate Index\nMulti-tenant: WMT/SAMS/INTL")]
    end

    subgraph AdServing["⚡ Ad Serving Core (latency-critical)"]
        SPADE["**midas-spade**\nJava 17 · Spring Boot 3.5.6 · AsyncHTTP\nOrchestrator: parallel fan-out to all downstream"]
        SPECTOR["**midas-spector**\nJava 17 · Spring Boot 2.6.6 · gRPC 1.52\nSharded bidding: orchestrator + N shard nodes\nSpBidsV2Rpc · BidsServiceRpc"]
        ABRAM["**abram**\nScala 2.12 · Play 2.7 · Akka · Slick\nTSP auction · Darwin/AdGenie retrieval\nKafka: feature-log producer"]
    end

    subgraph MLScoring["🧠 ML Scoring Layer"]
        SPARKLE["**sparkle**\nJava 21 · Spring Boot 3.5.0 · Feast 0.1.4\nParallel feature fetch → ML relevance scoring"]
        DAVINCI["**davinci**\nJava 21 · Spring WebFlux · Feast 0.1.5\n4-Level Cache: Caffeine→MeghaCache→Cassandra→Triton\nVectorGenerationController · AdRelevanceModelScoreController"]
    end

    subgraph MLInfra["🖥️ ML Infrastructure"]
        EL_DAVINCI["**element-davinci**\nNVIDIA Triton Server 24.12\nH100 80GB GPUs · 16 prod replicas\n3 Azure DCs (SCUS/WUSE2/EUS2)\nModels: ttb_emb, relevance_v1, universal_r1,\nmultimodal_v2, comp_retrieval"]
        EL_SS["**element-ss-inference**\nNVIDIA Triton Server 22.12 (LEGACY)\nTesla V100 · 1 prod replica\nBERT/DistilBERT/TTB · 169-dim output\nLast commit: Dec 2023"]
    end

    subgraph FeatureStore["📦 Feature Store Pipeline"]
        FEAST_SVC["**sp-adserver-feast**\nJava 17 · Spring Boot 3 · Feast SDK\nOnline serving via Cassandra CQL\nSPOnlineServingService · 7 FeatureServices\n5 FeatureViews: item_features, cnsldv2,\nitems_rate, pvpc, item_quality"]
        FEAST_BATCH["**element-adserver-feast**\nPython 3.11 · PySpark 3.3 · Feast\n@batch_feature_generator decorator\nDataproc: 28 executors / 20GB each\nBigQuery → GCS → Cassandra"]
        CASSANDRA[("Cassandra 4.x\nmidas keyspace\nTTL 182 days\n100 partitions")]
        BQ[("Google BigQuery\nAds Event Source\n+ Feature Output Tables")]
    end

    subgraph ClickBudget["💰 Click Tracking & Budget"]
        CRS["**sp-crs**\nJava 17 · Spring Boot 3.5.6\nClick dedup (Cassandra TTL 900s)\nSOX Kafka logging · WMT + SAMS tenants"]
        BUDDY["**sp-buddy**\nJava 17 · Spring Boot 3.5.6 · Kafka Streams\nBudget pacing · daily rollover at midnight\nWMT + WAP-MX + WAP-CA tenants"]
    end

    %% Advertiser → System
    MARTY_FE --> MARTY
    MARTY -->|REST / Argo| DARPA
    DARPA -->|"Kafka: campaign-events"| RADPUB
    RADPUB --> SOLR_VESPA

    %% Shopper ad serving fan-out
    SPADE --> SPECTOR
    SPADE --> ABRAM
    SPADE --> SPARKLE
    SPADE --> DAVINCI
    SPADE --> BUDDY

    %% Auction internals
    ABRAM --> SOLR_VESPA
    ABRAM --> SPECTOR
    ABRAM --> SPARKLE

    %% ML pipeline
    SPARKLE --> FEAST_SVC
    DAVINCI --> FEAST_SVC
    FEAST_SVC --> CASSANDRA
    DAVINCI -->|"gRPC Triton V2 :8001"| EL_DAVINCI

    %% Feature batch
    FEAST_BATCH --> BQ
    FEAST_BATCH --> CASSANDRA

    %% Click → Budget
    SPADE -.->|"redirect URL in ad response"| CRS
    CRS -->|"Kafka: click-events (SOX)"| BUDDY

    %% Experiment
    ACE --> BUDDY
    ACE -.->|"bucket lookup"| SPADE
```

---

## Technology Matrix

| Service | Language | Framework | Port | Primary DB | Messaging | Key External |
|---------|----------|-----------|------|-----------|-----------|--------------|
| sp-ace | Java 17 | Spring Boot 3.5.7 | 8080 | Azure SQL | — | EhCache, Memcached |
| sp-crs | Java 17 | Spring Boot 3.5.6 | 8080 | Cassandra + Azure SQL | Kafka (SOX) producer | Hawkshaw (SOX) |
| sp-buddy | Java 17 | Spring Boot 3.5.6 | 8080 | Azure SQL | Kafka Streams (both) | sp-crs, sp-ace |
| darpa | Scala 2.12 | Play 2.7 + Akka | 9000 | Oracle + Azure SQL | Kafka producer | IRO, WPA, Azure Blob |
| radpub-v2 | Java 17 | Spring Boot 3.5.6 | 8080 | Cassandra + Solr | Kafka consumer | Vespa, IRO |
| midas-spade | Java 17 | Spring Boot 3.5.6 | 8080 | Cassandra + Azure SQL | Kafka producer (log) | abram, spector, sparkle, davinci |
| midas-spector | Java 17 | Spring Boot 2.6.6 | 8080/gRPC | Azure SQL + Memcached | — | abram (gRPC client) |
| davinci | Java 21 | Spring WebFlux | 8080 | Cassandra + MeghaCache | — | element-davinci (gRPC), sp-adserver-feast |
| sparkle | Java 21 | Spring Boot 3.5.0 | 8080 | Cassandra | — | sp-adserver-feast (Feast) |
| abram | Scala 2.12 | Play 2.7 + Akka | 9000 | Azure SQL + Cassandra | Kafka producer (GCP) | midas-spector (gRPC), sparkle, Solr |
| marty | Python 3.11 | FastAPI + LangGraph | 8000 | Azure SQL | — | LLM Gateway, darpa, MCP tools |
| marty-fe | Python 3.11 | FastAPI + LangGraph | 8000 | Azure SQL | — | LLM Gateway (same as marty) |
| sp-adserver-feast | Java 17 + Python 3 | Spring Boot + Feast SDK | 8080 | Cassandra (midas ks) | — | GCS (registry), Akeyless |
| element-adserver-feast | Python 3.11 | PySpark 3.3 + Feast | N/A (batch) | BigQuery + GCS | — | Dataproc, Looper, Concord |
| element-davinci | Python 3 | Triton Server 24.12 | 8000/8001/8002 | Azure Blob (models) | — | H100 GPU, Akeyless |
| element-ss-inference | Python 3 | Triton Server 22.12 | 8000/8001/8002 | Azure Blob (models) | — | V100 GPU (legacy) |

---

## Data Flow Overview

```mermaid
sequenceDiagram
    actor Advertiser
    participant darpa as darpa (Campaign Mgmt)
    participant kafka as Kafka
    participant radpub as radpub-v2
    participant solr as Solr/Vespa
    actor Shopper
    participant spade as midas-spade
    participant abram as abram
    participant sparkle as sparkle
    participant davinci as davinci
    participant triton as element-davinci (Triton H100)
    participant feast as sp-adserver-feast
    participant cassandra as Cassandra
    participant crs as sp-crs
    participant buddy as sp-buddy

    Advertiser->>darpa: POST /campaigns (create campaign)
    darpa->>kafka: publish campaign-event
    kafka->>radpub: consume campaign-event
    radpub->>solr: index ad item (Solr + Vespa)

    Shopper->>spade: GET /v3/sp/ads?query=...
    spade->>abram: POST /v3/sp/search (async)
    spade->>davinci: GET /v3/vector (async)
    spade->>sparkle: POST /v3/scores (async)
    spade->>buddy: GET /budget/status (async)

    abram->>solr: query ad candidates
    abram->>sparkle: score candidates
    sparkle->>feast: getOnlineFeaturesAsync(itemIds)
    feast->>cassandra: CQL SELECT features WHERE entity_key=?
    cassandra-->>feast: feature rows
    feast-->>sparkle: feature proto
    sparkle-->>abram: relevance scores

    davinci->>cassandra: L3 cache lookup
    cassandra-->>davinci: MISS
    davinci->>triton: gRPC ModelInfer (ensemble_ttb_emb)
    triton-->>davinci: float[512] embeddings
    davinci->>cassandra: write-back L3

    abram->>abram: TSP auction (True Second Price)
    abram-->>spade: ranked ad list

    spade-->>Shopper: ad response with tracking URLs

    Shopper->>crs: GET /track?... (click)
    crs->>cassandra: dedup check (TTL 900s)
    crs->>kafka: publish click-event (SOX cluster)
    kafka->>buddy: consume click-event
    buddy->>buddy: decrement budget
```

---

## Deployment Topology

```mermaid
graph LR
    subgraph AKS["WCNP — Azure Kubernetes Service"]
        subgraph SCUS["South Central US (Primary)"]
            A1["midas-spade\nmidas-spector\nabram\ndavinci\nsparkle\nsp-crs\nsp-buddy"]
            A2["element-davinci\nH100 × 6 pods"]
        end
        subgraph WUSE2["West US 2"]
            B1["midas-spade\nmidas-spector\nabram\ndavinci\nsparkle"]
            B2["element-davinci\nH100 × 5 pods"]
        end
        subgraph EUS2["East US 2"]
            C1["midas-spade\nmidas-spector\nabram\ndavinci\nsparkle"]
            C2["element-davinci\nH100 × 5 pods"]
        end
        subgraph Shared["Shared Region Services"]
            D1["darpa · sp-ace\nradpub-v2 (WMT/SAMS/INTL)\nsp-adserver-feast\nmarty · marty-fe\nelement-ss-inference (V100 × 1)"]
        end
    end
    subgraph GCP["Google Cloud Platform"]
        G1["Dataproc Clusters\nelement-adserver-feast\nBigQuery"]
    end
```

---

## Config Management & Observability

| Concern | Solution | Details |
|---------|---------|---------|
| Config | CCM2 (Tunr / Strati AF BOM) | All Java/Scala; dynamic properties, per-env overrides |
| Secrets | Akeyless | DB creds, API keys, Triton auth tokens |
| Deployments | WCNP sr.yaml | Rate limits, replicas, egress routes, GPU requests |
| CI/CD | Concord (Walmart internal) | Element repos; Looper triggers batch jobs |
| Metrics | GTP Observability BOM (Micrometer + Prometheus) | All services; Grafana dashboards |
| Tracing | OpenTelemetry 1.49.0 | davinci, sparkle, element-davinci |
| API Docs | SpringDoc OpenAPI 2.3/2.6 | midas-spade, midas-spector, sparkle, davinci |
| Feature Registry | GCS (Feast registry path) | sp-adserver-feast; per-tenant prefix |

---

## Model Storage & Serving

| Artifact | Location | Loader | Notes |
|----------|---------|--------|-------|
| Triton models (H100) | `wasbs://ss-inference-models/.../prod_inference_models/` | Triton on startup | element-davinci; 7 model families |
| Triton models (V100) | `wasbs://ss-inference-models/.../triton_models_v1/` | Triton on startup | element-ss-inference; BERT/DistilBERT/TTB |
| Feast feature registry | `gs://...feast-registry/` | Feast SDK at startup | sp-adserver-feast; per-tenant GCS path |
| Batch feature output | BigQuery tables + GCS Parquet | PySpark job | element-adserver-feast; Looper triggered |

---

*Generated by Wibey CLI — `claude-sonnet-4-6-thinking` — March 2026*
