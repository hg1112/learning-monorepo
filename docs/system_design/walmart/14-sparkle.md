# Chapter 14 — sparkle (Relevance Scoring Service)

## 1. Overview

**sparkle** is the **ad relevance scoring engine** for Walmart Sponsored Products. Given a set of ad candidates, it computes relevance scores using ML feature data sourced from Cassandra, Azure SQL, and the **sp-adserver-feast** online feature store (Chapter 16). Scores are consumed by **abram** to rank ad candidates before auction. It also serves feature definitions used during scoring model evaluation.

> **Feature Store note:** The "Feast Feature Store" reference in this service maps to **`sp-adserver-feast`** (Chapter 16) — a Java Spring Boot service that wraps Apache Feast and serves pre-computed item features from Cassandra (`midas` keyspace). Features are batch-computed by **`element-adserver-feast`** (Chapter 17).

- **Domain:** ML-Driven Relevance Scoring
- **Tech:** Java 21, Spring Boot 3.5.0, Cassandra 4.19, sp-adserver-feast (Feast), Protobuf
- **WCNP Namespace:** `sparkle-wmt`
- **Port:** 8080
- **Swagger:** `https://sparkle-wmt.prod.walmart.com/docs`

---

## 2. Architecture Diagram

```mermaid
graph LR
  subgraph sparkle
    SCORE_V3["ScoringControllerV3\nPOST /v3/scores\nPOST /v3/scoresAsJson"]
    FEAT_DEF["FeatureDefinitionControllerV2\nGET /v2/models/{modelId}/feature-definitions"]
    HEALTH["HealthController\n/start + /live + /ready"]
    ADMIN["AdminController\n/app-config"]

    SCORE_SVC["Scoring Service\n(ML model evaluation)"]
    FEAT_META_DAO["FeatureMetaDataDao\n(Azure SQL)"]
    PAIRWISE_DAO["PairwiseFeaturesDao\n(Cassandra)"]
    CONTEXT_DAO["ContextFeaturesDao\n(Cassandra)"]
    FEAST_DAO["FeastFeaturesDao\n(Feast Feature Store)"]

    SCORE_V3 --> SCORE_SVC
    FEAT_DEF --> FEAT_META_DAO
    SCORE_SVC --> FEAT_META_DAO
    SCORE_SVC --> PAIRWISE_DAO
    SCORE_SVC --> CONTEXT_DAO
    SCORE_SVC --> FEAST_DAO
  end

  ABRAM["abram\n(HTTP caller)"]
  AZURE_SQL[("Azure SQL\nFeature metadata")]
  CASSANDRA[("Cassandra\nPairwise + context features")]
  SP_FEAST["sp-adserver-feast\n(Online Feature Serving — Java)\nSee Chapter 16"]
  MEGHACACHE[("MeghaCache\nScore cache")]

  ABRAM -->|"POST /v3/scores (Protobuf)"| SCORE_V3
  FEAT_META_DAO -->|"JDBC / JdbcTemplate"| AZURE_SQL
  PAIRWISE_DAO -->|"CqlSession"| CASSANDRA
  CONTEXT_DAO -->|"CqlSession"| CASSANDRA
  FEAST_DAO -->|"SPServingService.getOnlineFeaturesAsync()\nitem_features, ad_relevance_model_features"| SP_FEAST
  SCORE_SVC -->|"Write/read score cache"| MEGHACACHE
```

---

## 3. API / Interface

| Method | Path | Protocol | Description |
|--------|------|----------|-------------|
| POST | `/v3/scores` | Protobuf | Compute relevance scores (Protobuf response) |
| POST | `/v3/scoresAsJson` | JSON | Compute relevance scores (JSON response) |
| GET | `/v2/models/{modelId}/feature-definitions` | JSON | Get feature definitions for a model |
| GET | `/start` | JSON | K8s startup probe |
| GET | `/live` | JSON | K8s liveness probe |
| GET | `/ready` | JSON | K8s readiness probe |
| GET | `/app-config` | JSON | All CCM config modules |
| GET | `/app-config/{module}` | JSON | Config by module |

**Request:** `ScoringRequestV3` — contains ad candidates with `AdCandidate` objects (candidateId, itemId, features)
**Response:** `ScoringResponseV3` — per-candidate relevance scores (float)

---

## 4. Data Model

```mermaid
erDiagram
  SCORING_REQUEST ||--o{ AD_CANDIDATE : "scores"
  AD_CANDIDATE ||--o{ ITEM_FEATURES : "enriched with"
  FEATURE_META_DATA ||--o{ AD_CANDIDATE : "defines features for"

  AD_CANDIDATE {
    string candidateId
    string itemId
    map entities
    map features
  }

  FEATURE_META_DATA {
    string featureId PK
    string modelId
    string featureName
    string featureType
    boolean isActive
  }

  SCORING_RESPONSE {
    string candidateId
    float relevanceScore
    float qualityScore
    object debugInfo
  }
```

---

## 5. Inter-Service Dependencies

```mermaid
graph TD
  abram["abram\n(POST /v3/scores)"]
  sparkle["sparkle"]
  azure_sql[("Azure SQL\nfeature_metadata table")]
  cassandra[("Cassandra\npairwise + context features")]
  feast["Feast Feature Store\nitem-level features"]
  meghacache[("MeghaCache\nscore cache")]

  abram -->|"HTTP Protobuf POST /v3/scores"| sparkle
  sparkle -->|"JDBC SELECT feature_metadata"| azure_sql
  sparkle -->|"CQL pairwise_features"| cassandra
  sparkle -->|"CQL context_features"| cassandra
  sparkle -->|"SPServingService.getOnlineFeaturesAsync()\nFeatureService: ad_relevance_model_features"| sp_feast["sp-adserver-feast\n(Chapter 16)"]
  sp_feast -->|"CQL SELECT feature_name, value"| cassandra
  sparkle -->|"WmClient (Memcached)"| meghacache
```

---

## 6. Configuration

| Config Key | Description |
|-----------|-------------|
| `azureSql.host` | Azure SQL hostname |
| `azureSql.dbName` | Database name |
| `cassandra.host/port` | Cassandra cluster |
| `runtime.context.appName` | `sparkle-service` |
| `ccm.configs.dir` | CCM config directory |
| `scm.server.access.enabled` | SCM integration toggle |
| `spring.profiles.active` | `local`, `stg`, `prod`, `wcnp_*` |
| `efs_sdk_env` | EFS (Element Feature Store) SDK environment; JVM system property added to all deploy stages (Apr 2026) |
| `localDcUPS` | Local DC UPS (User Preference Service) endpoint; JVM system property for Cassandra DC-aware routing |

### EFS SDK Integration (Apr 2026)

Sparkle now integrates with the **Element Feature Store (EFS) SDK** to fetch the model registry
at service startup. This enables the scoring service to discover active model configurations
(model IDs, feature sets, thresholds) without requiring a code deploy.

**JVM system properties added to all deploy stages (`sr.yaml`):**
```
-Defs_sdk_env=<env>
-DlocalDcUPS=<dc-endpoint>
```

The `EFS Integration to fetch registry on bootup` change means that on pod startup, Sparkle
contacts the EFS SDK to load the current model registry, which determines which ML models are
active for CTR/CVR scoring. Previously this was baked into the Spring application context.
Akeyless secrets integration is also live in non-prod environments (production pending).

---

## 7. Example Scenario — Scoring Ad Candidates

```mermaid
sequenceDiagram
  participant ABRAM as abram (Auction Engine)
  participant SPARKLE as sparkle /v3/scores
  participant SQL as Azure SQL (feature_metadata)
  participant CASS as Cassandra (pairwise)
  participant FEAST as Feast (item features)
  participant MEGA as MeghaCache

  ABRAM->>SPARKLE: POST /v3/scores\n{candidates: [{itemId, bid, query, context}, ...]}

  SPARKLE->>MEGA: Check score cache {candidateIds}
  MEGA-->>SPARKLE: partial miss

  SPARKLE->>SQL: SELECT feature definitions for modelId
  SQL-->>SPARKLE: {featureNames, featureTypes}

  par Feature fetching
    SPARKLE->>CASS: SELECT pairwise_features {query, itemId}
    SPARKLE->>CASS: SELECT context_features {pageType, userId}
    SPARKLE->>FEAST: GET item features {itemIds}
  end

  CASS-->>SPARKLE: {pairwise: [...]}
  FEAST-->>SPARKLE: {itemFeatures: [...]}

  SPARKLE->>SPARKLE: Score candidates using ML model\n(feature vector × weights)
  SPARKLE->>MEGA: Write scores to cache (async)

  SPARKLE-->>ABRAM: 200 ScoringResponseV3\n{scores: [{candidateId, score: 0.87}, ...]}
  Note over ABRAM: Sort candidates by (score × bid) for auction
```
