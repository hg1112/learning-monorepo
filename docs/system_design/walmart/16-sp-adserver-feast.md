# Chapter 16 — sp-adserver-feast (Online Feature Serving)

## 1. Overview

**sp-adserver-feast** is the **online feature store serving layer** for Walmart Sponsored Products. It wraps Apache Feast, exposing a Java/gRPC API that retrieves pre-computed ML features from Cassandra at millisecond latency for ad relevance models. Features are defined once in Python (`feature_definitions.py`) and consumed by **sparkle** and **davinci** during real-time inference.

- **Domain:** Online Feature Store — ML Feature Serving
- **Tech:** Java 17 + Spring Boot 3 (serving module), Python 3 (Feast feature definitions + materialization)
- **Online Store:** Cassandra (`midas` keyspace)
- **Offline Store:** GCS Parquet files → materialized via Feast
- **WCNP:** Deployed as a library/sidecar — consumed by sparkle and davinci pods
- **Port:** gRPC (internal)

---

## 2. Architecture Diagram

```mermaid
graph LR
  subgraph sp_adserver_feast["sp-adserver-feast"]
    SERVING["SPOnlineServingService\n(Java — Spring Boot 3)"]
    FACTORY["SPServingModuleFactory\n(initializes Feast registry)"]
    CASS_RETRIEVER["SPCassandraOnlineRetriever\n(async Datastax driver)"]
    REGISTRY["Feast Registry\n(GCS: registry.db)"]
    MATERIALIZER["sp_materialization_entrypoint.py\n(Feast fs.materialize())"]
    VALIDATOR["test_feature_definitions.py\n(CI validation)"]

    SERVING --> FACTORY
    FACTORY --> REGISTRY
    SERVING --> CASS_RETRIEVER
  end

  subgraph feature_repos["Feature Definitions (Python)"]
    SP_REPO["sp_feature_repo/\nfeature_definitions.py\n(5 FeatureViews, 10+ FeatureServices)"]
    WAP_REPO["wap_sp_feature_repo/\n(International — WAP)"]
  end

  SPARKLE["sparkle\n(relevance scoring)"]
  DAVINCI["davinci\n(ML vectors)"]
  CASSANDRA[("Cassandra\nkeyspace: midas\nTTL: 182 days")]
  GCS[("GCS\nParquet feature files\nadtech-ds-adhoc-dev/*")]
  CONCORD["Concord\n(materialization jobs)"]

  SPARKLE -->|"SPServingService.getOnlineFeaturesAsync()"| SERVING
  DAVINCI -->|"SPServingService.getOnlineFeaturesAsync()"| SERVING
  CASS_RETRIEVER -->|"SELECT feature_name, value, event_ts\nWHERE entity_key=? AND feature_name IN ?"| CASSANDRA
  MATERIALIZER -->|"fs.materialize()"| CASSANDRA
  GCS -->|"Parquet source files"| MATERIALIZER
  FACTORY -->|"Load registry.db"| GCS
  CONCORD -->|"Trigger materialization job"| MATERIALIZER
```

---

## 3. API / Interface

**Java Service Methods (`SPServingService`):**

| Method | Signature | Description |
|--------|-----------|-------------|
| `getOnlineFeaturesAsync` | `(String id, String service) → CompletableFuture<CassandraFeatures>` | Retrieve features as Java Map |
| `getOnlineFeaturesAsyncProto` | `(String id, String service) → CompletableFuture<FeatureResponse>` | Retrieve as Protobuf |
| `getOnlineFeaturesForTenantAsyncProto` | `(String id, String service, String tenant) → CompletableFuture<FeatureResponse>` | Multi-tenant retrieval |
| `getServiceNamesForFeatures` | `(List<String> features, String consumer) → List<String>` | Resolve feature service names |
| `getEntityNames` | `() → Set<String>` | Get all registered entities |

**Protobuf Response:**
```protobuf
message FeatureResponse {
  string entityKey = 1;
  int64 eventTs = 2;
  map<string, feast.types.Value> features = 3;
}
```

**Cassandra Query (Low-level):**
```sql
SELECT feature_name, value, event_ts
FROM midas.{featureViewName}
WHERE entity_key = :itemId
AND feature_name IN :features
```

---

## 4. Data Model

```mermaid
erDiagram
  ENTITY ||--o{ FEATURE_VIEW : "joins on"
  FEATURE_VIEW ||--o{ FEATURE : "contains"
  FEATURE_VIEW ||--o{ FEATURE_SERVICE : "grouped in"
  FEATURE_SERVICE ||--o{ ML_MODEL : "consumed by"

  ENTITY {
    string name
    string join_key
  }

  FEATURE_VIEW {
    string name
    string entity
    bool online
    string source_path
    string timestamp_field
  }

  FEATURE {
    string name
    string dtype
    string source_field
    string description
  }

  CASSANDRA_FEATURE_ROW {
    string entity_key PK
    string feature_name PK
    blob value
    timestamp event_ts
    timestamp created_ts
  }
```

**FeatureViews registered (sp_feature_repo):**

| FeatureView | Entity | # Fields | Source (GCS Parquet) | Description |
|-------------|--------|-----------|---------------------|-------------|
| `item_features` | item (item_id) | 50+ | `ds_wpa_ad_item_features_new_flattened` | Core item ad features (CTR, sales, price) |
| `cnsldv2_features` | item | ~20 | `cnsldv2_item_features` | Consolidated v2 item features |
| `items_rate_features` | item | ~15 | `ds_wpa_ad_items_rate_features_v2` | Rate-based features |
| `pvpc_features` | item | ~10 | `item_pvpc_features` | Price/value/position features |
| `item_quality_features` | item | ~10 | `item_quality_features` | Quality score features |

**FeatureServices (10+ registered):**

| FeatureService | Description | Consumer |
|---------------|-------------|----------|
| `ad_relevance_model_features` | Features for ad ranking model | sparkle |
| `universal_r1_ensemble_model_relevance_v1` | Universal R1 relevance | davinci/sparkle |
| `complementary_compatibility_ensemble_model_v5` | Complementary item scoring | sparkle |
| `universal_l1_r1_ensemble_model_relevance_v1` | L1+R1 ensemble | sparkle |
| `item_features` | All item features | general |
| `cnsldv2_service` | Consolidated v2 | general |
| `items_rate_feature_service` | Rate features | general |

---

## 5. Inter-Service Dependencies

```mermaid
graph TD
  sparkle["sparkle\n(relevance scoring)"]
  davinci["davinci\n(ML vectors)"]
  sp_feast["sp-adserver-feast\n(Java serving module)"]
  cassandra[("Cassandra\nmidas keyspace")]
  gcs[("GCS\nParquet feature files")]
  concord["Concord\n(materialization orchestration)"]
  element_feast["element-adserver-feast\n(batch feature generation)"]

  sparkle -->|"getOnlineFeaturesAsync(itemId, service)"| sp_feast
  davinci -->|"getOnlineFeaturesAsync(itemId, service)"| sp_feast
  sp_feast -->|"CQL: SELECT features WHERE entity_key=?"| cassandra
  concord -->|"Trigger: fs.materialize()"| sp_feast
  element_feast -->|"Write Parquet to GCS"| gcs
  gcs -->|"Feast batch source"| sp_feast
  sp_feast -->|"Materialize to online store"| cassandra
```

---

## 6. Configuration

| Config Key | Example | Description |
|-----------|---------|-------------|
| `feast.registry` | `gs://adtech-spadserver-artifacts-prod/feast/dev/registry.db` | GCS path to Feast registry |
| `feast.project` | `sp_adserver_feast` | Feast project name |
| `feast.activeStore` | `cassandra` | Active online store |
| `feast.registryRefreshInterval` | `60` | Registry refresh interval (seconds) |
| `feast.entityKeySerializationVersion` | `2` | Entity key serialization version |
| `feast.tenantPrefixMap` | `{WMT: "", WAP: "wap_"}` | Multi-tenant table prefix map |
| `feast.stores[0].config.hosts` | Cassandra cluster hosts | Cassandra connection |
| `feast.stores[0].config.keyspace` | `midas` | Cassandra keyspace |
| `CASSANDRA_USERNAME` | (Akeyless) | Cassandra auth |
| `CASSANDRA_PASSWORD` | (Akeyless) | Cassandra auth |
| `GOOGLE_APPLICATION_CREDENTIALS` | (Akeyless) | GCP service account JSON |

**Environments:** `dev/feature_store.yaml`, `prod/feature_store.yaml`, `wap_sp_feature_repo/dev/feature_store.yaml`

---

## 7. Example Scenario — Feature Retrieval During Ad Ranking

```mermaid
sequenceDiagram
  participant SPARKLE as sparkle (scoring)
  participant SP_FEAST as sp-adserver-feast (Java)
  participant REGISTRY as Feast Registry (GCS)
  participant CASSANDRA as Cassandra (midas)

  Note over SPARKLE: Received ScoringRequest with itemIds

  SPARKLE->>SP_FEAST: getOnlineFeaturesAsyncProto("item-9876", "ad_relevance_model_features")

  SP_FEAST->>REGISTRY: Resolve service → [item_features, pvpc_features]\n(cached in-memory, refresh every 60s)
  REGISTRY-->>SP_FEAST: FeatureService definition

  SP_FEAST->>CASSANDRA: SELECT feature_name, value, event_ts\nFROM midas.item_features\nWHERE entity_key='item-9876'\nAND feature_name IN ['adWpaCtr30d', 'adPriceAmt', ...]
  CASSANDRA-->>SP_FEAST: [{feature_name: "adWpaCtr30d", value: 0.045, event_ts: 2026-03-25}, ...]

  SP_FEAST->>SP_FEAST: Deserialize Feast Value proto
  SP_FEAST-->>SPARKLE: FeatureResponse {entityKey: "item-9876", features: {adWpaCtr30d: 0.045, ...}}
```

---

## 8. Materialization Flow (Batch → Online)

```mermaid
sequenceDiagram
  participant CONCORD as Concord (orchestration)
  participant JOB as sp_materialization_entrypoint.py
  participant GCS as GCS (Parquet)
  participant CASS as Cassandra (midas)
  participant REGISTRY as Feast Registry (GCS)

  Note over CONCORD: Triggered on schedule or PR merge
  CONCORD->>JOB: Execute custom_materializer(start_date, end_date, tenant)

  JOB->>REGISTRY: fs = FeatureStore(repo_path, config=feature_store.yaml)
  JOB->>GCS: Read Parquet files (item_features source)
  GCS-->>JOB: Feature rows (item_id, features, event_dt)

  JOB->>JOB: Apply SPCustomEngine.materialize() partitioned by item_id (100 partitions)
  JOB->>CASS: INSERT INTO midas.item_features\n(entity_key, feature_name, value, event_ts)\nWITH TTL 15778476

  Note over CASS: Features available for online serving\n(TTL ≈ 182 days)
```
