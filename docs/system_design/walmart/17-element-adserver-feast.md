# Chapter 17 — element-adserver-feast (Batch Feature Engineering Pipeline)

## 1. Overview

**element-adserver-feast** is the **batch feature engineering pipeline** for the Element AdServer platform. It runs on **Google Dataproc (PySpark)**, reads advertising event data from **BigQuery**, applies feature transformations, and materializes computed features to BigQuery tables and Cassandra — feeding the offline store consumed by `sp-adserver-feast` (Chapter 16).

- **Domain:** Offline Feature Engineering — ML Feature Generation
- **Tech:** Python 3.11, PySpark 3.3, Apache Spark on Google Dataproc
- **Pipeline Orchestrator:** Looper (Walmart MLOps) + Concord (Workflow)
- **Storage:** BigQuery (output) → GCS Parquet → Cassandra (via Feast materialization)
- **SDK:** `walmart-efs-featurestore-sdk==0.6.0`

---

## 2. Architecture Diagram

```mermaid
graph LR
  subgraph element_feast["element-adserver-feast (PySpark on Dataproc)"]
    ENTRY["feature_generation.py\n@batch_feature_generator\nbatch_processing_logic(spark, *args)"]
    PIPELINE["pipeline.yml\n(Looper pipeline definition)"]
    FEAT_ENG["feature_engineering.yml\n(Entity & FeatureSet definitions)"]
    CONDA["environment.yml\n(Python 3.11, walmart-efs-featurestore-sdk)"]

    PIPELINE --> ENTRY
    ENTRY --> FEAT_ENG
  end

  BQ_SOURCE[("BigQuery\n(ads event data source)")]
  GCS_TEMP[("GCS Temp Bucket\n(Spark temp data)")]
  BQ_OUTPUT[("BigQuery\n(feature output tables)")]
  CASSANDRA[("Cassandra\nvia Feast materialization")]
  LOOPER["Looper\n(Walmart CI/CD + MLOps)"]
  CONCORD["Concord\n(Workflow + Secrets)"]
  AKEYLESS["Akeyless Vault\n(client_id, client_secret)"]

  LOOPER -->|"Schedule + trigger"| PIPELINE
  CONCORD -->|"Pre-build: register features"| element_feast
  ENTRY -->|"spark.read.format('bigquery')"| BQ_SOURCE
  ENTRY -->|"Spark temp data"| GCS_TEMP
  ENTRY -->|"Feature output tables"| BQ_OUTPUT
  CONCORD -->|"Inject secrets"| AKEYLESS
  BQ_OUTPUT -.->|"Materialized via sp-adserver-feast"| CASSANDRA
```

---

## 3. API / Interface

**element-adserver-feast has no REST or gRPC API.** It is a pure **batch pipeline** triggered by Looper on a schedule.

**Pipeline Entry Point:**
```python
# /pipelines/data_prep/data_prep/src/feature_generation.py
@batch_feature_generator
def batch_processing_logic(spark: SparkSession, *args, **kwargs) -> DataFrame:
    # 1. Read from BigQuery
    # 2. Apply feature transformations
    # 3. Return DataFrame (Feast SDK handles write)
    pass
```

**Looper Pipeline Trigger:**
- Manual or scheduled via Looper platform
- Node type: `pyspark` (Dataproc cluster)
- Cluster lifecycle: creates and deletes `test-cluster` per run

---

## 4. Data Model

```mermaid
erDiagram
  ENTITY ||--o{ FEATURE_SET : "defines"
  FEATURE_SET ||--o{ FEATURE : "contains"
  FEATURE_SET ||--|| DATA_SOURCE : "reads from"
  DATA_SOURCE ||--|| BIG_QUERY : "or"
  DATA_SOURCE ||--|| GCS_SPARK : "stored in"

  ENTITY {
    string name
    string adGroup
    string businessOwner
    string valueType
  }

  FEATURE_SET {
    string name
    string datasourceName
    list entityKeys
    map entityMapping
    list features
    string saveMode
    bool enablePartition
  }

  FEATURE {
    string name
    string dataType
    string sourceField
    string description
  }

  DATA_SOURCE {
    string type
    string projectName
    string datasetName
    string tableName
    string timestampField
    string partitionType
  }
```

**Supported Entity Value Types:** `BYTES`, `STRING`, `INT32`, `INT64`, `DOUBLE`, `FLOAT`, `BOOL`, `UNIX_TIMESTAMP`, and `*_LIST` variants

**Supported Save Modes:** `append`, `overwrite`

**Supported Partition Types:** `HOUR`, `DAY`, `MONTH`, `YEAR`

---

## 5. Inter-Service Dependencies

```mermaid
graph TD
  looper["Looper\n(MLOps orchestration)"]
  concord["Concord\n(workflow + pre-build hooks)"]
  element_feast["element-adserver-feast\n(PySpark pipeline)"]
  bigquery[("BigQuery\n(ads event source data)")]
  gcs_temp[("GCS Temp Bucket\n(Spark shuffle)")]
  bigquery_out[("BigQuery\n(feature output tables)")]
  cassandra[("Cassandra\n(via Feast SDK write-back)")]
  akeyless["Akeyless Vault\n(client_id, client_secret)"]
  sp_feast["sp-adserver-feast\n(reads output via GCS Parquet)"]

  looper -->|"Trigger pipeline"| element_feast
  concord -->|"Inject secrets + register features"| element_feast
  akeyless -->|"client_id, client_secret_path"| element_feast
  element_feast -->|"spark.read.format('bigquery')"| bigquery
  element_feast -->|"Spark temp materialization"| gcs_temp
  element_feast -->|"Write feature tables"| bigquery_out
  element_feast -->|"walmart-efs-featurestore-sdk\nwrite to online store"| cassandra
  bigquery_out -.->|"Exported as Parquet"| sp_feast
```

---

## 6. Configuration

| Config Key | Required | Description |
|-----------|----------|-------------|
| `client_id` | Yes | Akeyless authentication client ID |
| `client_secret_path` | Yes | Akeyless vault secret path |
| `store.output_source.sourceMetadata.projectName` | Yes | BigQuery GCP project |
| `store.output_source.sourceMetadata.datasetName` | Yes | BigQuery dataset name |
| `store.output_source.sourceMetadata.tableName` | Yes | BigQuery target table |
| `store.entities[].name` | Yes | Entity identifier name |
| `store.entities[].valueType` | Yes | Entity type (INT64, STRING, etc.) |
| `store.feature_sets[].name` | Yes | Feature set name |
| `store.feature_sets[].entityKeys` | Yes | Join keys for entity mapping |
| `LOGICAL_TIME` | (Looper-set) | Pipeline execution timestamp |
| `BUILD_NUMBER` | (Jenkins-set) | CI build number |

**Spark Resource Allocation (pipeline.yml):**
| Resource | Value |
|----------|-------|
| Driver cores | 3 |
| Driver memory | 12 GB |
| Executor cores | 3 |
| Executor memory | 20 GB |
| Executor count | 28 |

**Spark BigQuery package:** `spark-bigquery-with-dependencies_2.12:0.42.0`

---

## 7. Example Scenario — Feature Generation Run

```mermaid
sequenceDiagram
  participant LOOPER as Looper (MLOps)
  participant CONCORD as Concord (pre-build)
  participant DATAPROC as Dataproc (PySpark)
  participant BQ_IN as BigQuery (ads events)
  participant GCS as GCS Temp Bucket
  participant BQ_OUT as BigQuery (features)
  participant CASS as Cassandra (Feast SDK)

  LOOPER->>CONCORD: Trigger pre-build hook (feature registration)
  CONCORD->>DATAPROC: Register feature definitions via Feast SDK

  LOOPER->>DATAPROC: Create Dataproc cluster (test-cluster)
  DATAPROC->>DATAPROC: Deploy feature_generation.py

  DATAPROC->>BQ_IN: spark.read.format("bigquery")\n.option("table", "project.dataset.ads_events")
  BQ_IN-->>DATAPROC: Advertising event DataFrame (billions of rows)

  Note over DATAPROC: Feature transformations:\n- CTR computation (clicks/impressions)\n- 30-day rolling sales\n- Price percentile features
  DATAPROC->>GCS: Spark temp shuffle (temporaryGcsBucket)
  DATAPROC->>BQ_OUT: Write feature table\n{item_id, adWpaCtr30d, adPriceAmt, ...}

  Note over DATAPROC: walmart-efs-featurestore-sdk writes to online store
  DATAPROC->>CASS: INSERT INTO midas.item_features\n(entity_key, feature_name, value, event_ts)

  LOOPER->>DATAPROC: Delete Dataproc cluster (test-cluster)
  Note over BQ_OUT,CASS: Features available for sp-adserver-feast serving
```
