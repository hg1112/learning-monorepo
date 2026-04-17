# Chapter 6 — sp-ace (Advertising Controlled Experimentation)

## 1. Overview

**sp-ace** is Walmart's A/B testing platform for the Sponsored Products advertising stack. It enables controlled experimentation across the entire ad serving pipeline — from budget pacing to auction logic — by partitioning campaigns and traffic into experiment groups (buckets).

- **Domain:** Experimentation & Feature Flagging
- **Tech:** Java 17, Spring Boot 3.5.7, OpenAPI-generated REST, Hibernate Envers (audit)
- **WCNP Namespace:** `sp-ace`
- **Port:** 8080
- **Swagger:** `http://localhost:8080/swagger-ui/index.html`

---

## 2. Architecture Diagram

```mermaid
graph LR
  subgraph sp-ace
    API["OpenAPI REST Controller\n(Generated Delegates)"]
    SVC["Service Layer\n(Business Logic)"]
    REPO["JPA Repositories\n(Spring Data)"]
    CACHE["EHCache\n(300s TTL)"]
    AUDIT["Hibernate Envers\n(Audit Trail)"]
    SCHED["Scheduled Jobs\n(@EnableScheduling)"]

    API --> SVC
    SVC --> REPO
    SVC --> CACHE
    REPO --> DB[("Azure SQL Server")]
    REPO --> AUDIT
    SCHED --> SVC
  end

  BUDDY["sp-buddy\n(Budget Service)"]
  SPOTLIGHT["Spotlight\n(Notifications)"]

  SVC -->|"HTTP BuddyClient\n(budget operations)"| BUDDY
  SVC -->|"HTTP SpotlightClient\n(experiment updates)"| SPOTLIGHT
```

---

## 3. API / Interface

| Method | Path | Description |
|--------|------|-------------|
| POST | `/v1/experiments` | Create experiment (supports multi-cell, CARADS-45937) |
| GET | `/v1/experiments` | List experiments (with pagination) |
| GET | `/v1/experiments/{id}` | Get experiment details |
| PUT | `/v1/experiments/{id}/state` | Transition experiment state |
| GET | `/v1/experiments/{id}/history` | Audit history (Hibernate Envers) |
| POST | `/v1/experiments/{id}/clone` | Clone experiment |
| POST | `/v1/experiments/{id}/buckets` | Create bucket |
| PUT | `/v1/experiments/{id}/buckets/{bucketId}/state` | Update bucket state |
| GET | `/adserving/v1/experiments/active` | **Ad Serving** — active experiments |
| GET | `/adserving/v1/bucket-keys/enabled` | **Ad Serving** — enabled bucket keys |
| GET | `/budget/v1/experiments/active` | **Budget** — active budget experiments |
| PUT | `/housekeeping/v1/experiments` | Housekeeping scheduler |
| GET | `/v1/layers` | Get layer-to-app mappings |
| GET | `/v3/user-access` | Get user permissions across tenants |

**Auth:** Internal service auth via CCM `AuthConfig`. Approval flow configurable via `approval.flow.enabled`.

---

## 4. Data Model

```mermaid
erDiagram
  EXPERIMENTS ||--o{ BUCKETS : "partitions"
  BUCKETS ||--o{ BUCKET_KEY_CATEGORIES : "categorized by"
  BUCKET_KEY_CATEGORIES ||--o{ BUCKET_KEYS : "contains"
  EXPERIMENTS ||--o{ REVIEWS : "requires"
  EXPERIMENTS ||--o{ EXPERIMENT_SAMPLING_RATES : "samples via"
  LAYERS ||--o{ EXPERIMENTS : "groups"
  USERS ||--o{ USER_ACCESS : "granted"
  EXPERIMENTS ||--o{ EXPERIMENTS_AUD : "audited in"

  EXPERIMENTS {
    long id PK
    string appName
    string layer
    string label
    string state
    string sampleMethod
    boolean expoControl
    string description
    json rules
    timestamp startTime
    timestamp endTime
    string tenant
    int cellCount "Multi-cell support (CARADS-45937)"
    string incrementalityType "SearchIncrementalityExperiment type"
    float controlAllocationPercent "Control cell allocation"
    boolean multiCellEnabled "Multi-cell flag"
    string cellAssignmentStrategy "How traffic is split across cells"
    string analysisUnit "Experiment unit (CAMPAIGN / AD_GROUP)"
  }
  BUCKETS {
    long id PK
    long experimentId FK
    string label
    float allocationPercent
    string state
  }
  BUCKET_KEYS {
    long id PK
    long bucketKeyCategoryId FK
    string keyValue
    boolean enabled
  }
```

---

## 5. Inter-Service Dependencies

```mermaid
graph TD
  sp_ace["sp-ace"]
  sp_buddy["sp-buddy\n(BuddyClient)"]
  spotlight["Spotlight\n(SpotlightClient)"]
  ad_serving["Ad Serving Pipeline\n(midas-spector, abram)"]
  azure_sql[("Azure SQL")]

  sp_ace -->|"HTTP: budget operations\n(buddy.url config)"| sp_buddy
  sp_ace -->|"HTTP POST /v3/events\n(spotlight.url config)"| spotlight
  ad_serving -->|"GET /adserving/v1/experiments/active\n(polling)"| sp_ace
  sp_ace -->|"JPA read/write"| azure_sql
```

---

## 6. Configuration

| Config Key | Default | Description |
|-----------|---------|-------------|
| `buddy.url` | `https://sp-buddy-wmt.dev.walmart.com` | Budget service URL |
| `spotlight.url` | `http://api.spotlight.stg.walmart.com/api` | Notification service URL |
| `spotlight.enabled` | `false` | Enable experiment update notifications |
| `approval.flow.enabled` | `false` | Require review before activation |
| `cache.enabled` | `false` | Enable EHCache |
| `cache.timeout.ace` | `300` | Experiment cache TTL (seconds) |
| `readonly.instance` | `false` | Read-only mode |
| `sublayer.max.experiments` | `1` | Max concurrent experiments per sublayer |
| `multi.cell.experiments.enabled` | `false` | Enable multi-cell experiment support (CARADS-45937) |
| `default.page.size` | `50` | Default pagination size |
| `ace.encryption.enabled` | `true` | Encrypt sensitive fields |

---

## 7. Example Scenario — Creating and Activating a Budget Experiment

```mermaid
sequenceDiagram
  actor PM as Product Manager
  participant ACE as sp-ace
  participant DB as Azure SQL
  participant BUDDY as sp-buddy
  participant SPOTLIGHT as Spotlight

  PM->>ACE: POST /v1/experiments\n{appName: "sp-buddy", layer: "budget-pacing", tenant: "WMT"}
  ACE->>DB: INSERT INTO EXPERIMENTS (state=DRAFT)
  ACE-->>PM: 201 {experimentId: 42}

  PM->>ACE: POST /v1/experiments/42/buckets\n{label: "control", allocationPercent: 50}
  ACE->>DB: INSERT INTO BUCKETS
  ACE-->>PM: 201 {bucketId: 101}

  PM->>ACE: PUT /v1/experiments/42/state\n{state: RUNNING}
  ACE->>DB: UPDATE EXPERIMENTS SET state=RUNNING
  ACE->>BUDDY: HTTP BuddyClient (budget integration)
  ACE->>SPOTLIGHT: POST /v3/events {eventType: experiment.update}
  ACE-->>PM: 200 {state: RUNNING}

  Note over ACE: Scheduled housekeeping job checks expired experiments
  ACE->>DB: SELECT active experiments past endTime
  ACE->>DB: UPDATE state=COMPLETED
```

---

## 8. Experiment State Machine

```mermaid
stateDiagram-v2
  [*] --> DRAFT : Create
  DRAFT --> UNDER_REVIEW : Submit for approval
  UNDER_REVIEW --> DRAFT : Reject
  UNDER_REVIEW --> APPROVED : Approve
  APPROVED --> RUNNING : Activate
  RUNNING --> PAUSED : Pause
  PAUSED --> RUNNING : Resume
  RUNNING --> COMPLETED : End date reached
  RUNNING --> STOPPED : Manual stop
  COMPLETED --> [*]
  STOPPED --> [*]
```
