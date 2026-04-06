# Uber Mini-Backend — Build Guide

Implementation is split across nine phase files. Read this file first for environment setup and infra bootstrap, then follow the phase files in order.

| File | Covers |
|------|--------|
| `STEPS_1.md` | Phase 1 — Eats Service (MongoDB, MinIO, Kafka) |
| `STEPS_2.md` | Phase 2 — Campaign Management (fixed bids, 4 cache strategies, ad-created-events) |
| `STEPS_3.md` | Phase 3 — Rides Service (PostgreSQL/Citus, Redis GEO, Kafka) |
| `STEPS_4.md` | Phase 4 — Notification Worker + API Gateway (rate limiting) |
| `STEPS_5.md` | Phase 5 — Location Service (gRPC bidirectional stream + WebSocket push) |
| `STEPS_6.md` | Phase 6 — Ad Serving Service (Retrieval → DCN Ranking → Auction, gRPC) |
| `STEPS_7.md` | Phase 7 — ML Platform (Two-Tower, DCN v2, Triton, Qdrant, MLflow) |
| `STEPS_8.md` | Phase 8 — Triton Model Configs + ONNX export scripts |
| `STEPS_9.md` | Phase 9 — Feed BFF (GraphQL, parallel resolvers, WebSocket subscription) |

---

## Step 0A: Bazel Setup

Bazelisk is a version manager for Bazel. It reads `.bazelversion` and downloads the correct binary automatically. Install it once and alias it as `bazel`.

```bash
# Check if already available
which bazel && bazel version

# Install via npm (simplest, works on WSL/Linux/Mac)
npm install -g @bazel/bazelisk
echo 'alias bazel=bazelisk' >> ~/.bashrc && source ~/.bashrc

# OR via go
go install github.com/bazelbuild/bazelisk@latest
export PATH=$PATH:$(go env GOPATH)/bin
echo 'alias bazel=bazelisk' >> ~/.bashrc && source ~/.bashrc

# Verify
bazel version   # should print Bazel 7.4.1
```

---

## Step 0B: Docker Compose — All Infrastructure

Create `apps/uber/docker-compose.yml`. This starts every infrastructure component the backend needs.

```yaml
# apps/uber/docker-compose.yml
version: '3.8'

services:

  # ── Eats: document store ────────────────────────────────────────────────────
  mongodb:
    image: mongo:7.0
    container_name: uber-mongo
    ports:
      - "27017:27017"
    volumes:
      - mongo_data:/data/db

  # ── Ads + Rides: relational store with horizontal sharding ──────────────────
  # citusdata/citus image = PostgreSQL 16 + Citus extension pre-installed
  postgres:
    image: citusdata/citus:12.1
    container_name: uber-postgres
    environment:
      POSTGRES_USER: uber
      POSTGRES_PASSWORD: uber
      POSTGRES_DB: uber
    ports:
      - "5432:5432"
    volumes:
      - postgres_data:/var/lib/postgresql/data

  # ── Shared: GEO index, cache, rate-limit counters ──────────────────────────
  redis:
    image: redis:7.2
    container_name: uber-redis
    ports:
      - "6379:6379"
    # AOF persistence: flush every second. Protects write-behind cache data.
    command: redis-server --appendonly yes --appendfsync everysec

  # ── Shared: async event bus (KRaft mode — no Zookeeper) ────────────────────
  kafka:
    image: bitnami/kafka:3.7
    container_name: uber-kafka
    ports:
      - "9092:9092"
    environment:
      - KAFKA_CFG_NODE_ID=0
      - KAFKA_CFG_PROCESS_ROLES=controller,broker
      - KAFKA_CFG_LISTENERS=PLAINTEXT://:9092,CONTROLLER://:9093
      - KAFKA_CFG_LISTENER_SECURITY_PROTOCOL_MAP=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT
      - KAFKA_CFG_CONTROLLER_QUORUM_VOTERS=0@kafka:9093
      - KAFKA_CFG_CONTROLLER_LISTENER_NAMES=CONTROLLER
      - KAFKA_CFG_ADVERTISED_LISTENERS=PLAINTEXT://localhost:9092
      - KAFKA_CFG_AUTO_CREATE_TOPICS_ENABLE=true
      - ALLOW_PLAINTEXT_LISTENER=yes
    volumes:
      - kafka_data:/bitnami/kafka

  # ── Eats + Ads: object storage (S3-compatible API) ─────────────────────────
  minio:
    image: minio/minio:latest
    container_name: uber-minio
    ports:
      - "9000:9000"   # S3 API
      - "9001:9001"   # Web console → open http://localhost:9001
    environment:
      MINIO_ROOT_USER: minioadmin
      MINIO_ROOT_PASSWORD: minioadmin
    command: server /data --console-address ":9001"
    volumes:
      - minio_data:/data

  # ── ML Platform: vector DB for ad embeddings (ANN retrieval) ───────────────
  qdrant:
    image: qdrant/qdrant:latest
    container_name: uber-qdrant
    ports:
      - "6333:6333"   # REST API + gRPC
      - "6334:6334"   # gRPC (alternate)
    volumes:
      - qdrant_data:/qdrant/storage

  # ── ML Platform: model registry + experiment tracking ──────────────────────
  mlflow:
    image: ghcr.io/mlflow/mlflow:latest
    container_name: uber-mlflow
    ports:
      - "5001:5000"
    command: mlflow server --host 0.0.0.0 --backend-store-uri sqlite:///mlflow.db --default-artifact-root /mlruns
    volumes:
      - mlflow_data:/mlruns

  # ── ML Platform: NVIDIA Triton Inference Server (CPU mode) ─────────────────
  # Hosts user_tower, ad_tower, dcn_ranker ONNX models.
  # model-control-mode=poll: hot-reloads new model versions every 30s.
  # To use GPU: add `deploy: resources: reservations: devices: [{capabilities: [gpu]}]`
  triton:
    image: nvcr.io/nvidia/tritonserver:24.01-py3
    container_name: uber-triton
    ports:
      - "8000:8000"   # HTTP inference API
      - "8001:8001"   # gRPC inference API  ← ML Platform uses this
      - "8002:8002"   # Prometheus metrics
    volumes:
      - ./ml-platform/model_repository:/models
    command: >
      tritonserver
      --model-repository=/models
      --model-control-mode=poll
      --repository-poll-secs=30
      --log-verbose=0
    # NOTE: First run will pull ~15GB. Pull separately:
    # docker pull nvcr.io/nvidia/tritonserver:24.01-py3
    # Triton starts with no models loaded until model_repository is populated.
    # Run STEPS_8.md to export ONNX models before starting this container.

volumes:
  mongo_data:
  postgres_data:
  kafka_data:
  minio_data:
  qdrant_data:
  mlflow_data:
```

```bash
# Start everything
cd apps/uber
docker compose up -d

# Verify all containers are healthy
docker compose ps

# Stop everything (data persists in volumes)
docker compose down

# Stop + wipe all data
docker compose down -v
```

---

## Step 0C: PostgreSQL — Create Schemas + Enable Citus

After `docker compose up`, run the DDL to create the tables and configure Citus distributed sharding.

```bash
# Connect to Postgres
docker exec -it uber-postgres psql -U uber -d uber
```

```sql
-- ── Ads Service schema ─────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS ads (
    id               BIGSERIAL PRIMARY KEY,
    advertiser_id    BIGINT        NOT NULL,
    title            VARCHAR(255)  NOT NULL,
    description      TEXT,
    bid_amount       NUMERIC(10,4) NOT NULL,
    quality_score    NUMERIC(5,4)  NOT NULL DEFAULT 1.0,
    target_cuisine   VARCHAR(100),
    image_url        TEXT,
    status           VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
    daily_budget     NUMERIC(10,2),
    created_at       TIMESTAMPTZ   DEFAULT NOW()
);

-- Shard by advertiser_id so all campaigns from one advertiser live on one shard.
-- Cross-shard JOINs are expensive — never JOIN ads with rides.
SELECT create_distributed_table('ads', 'advertiser_id');

-- ── Rides Service schema ───────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS drivers (
    id           BIGSERIAL PRIMARY KEY,
    name         VARCHAR(255) NOT NULL,
    vehicle_plate VARCHAR(20),
    status       VARCHAR(20)  NOT NULL DEFAULT 'AVAILABLE'
);

CREATE TABLE IF NOT EXISTS rides (
    id           BIGSERIAL PRIMARY KEY,
    user_id      BIGINT       NOT NULL,
    driver_id    BIGINT,
    pickup_lat   DOUBLE PRECISION NOT NULL,
    pickup_lng   DOUBLE PRECISION NOT NULL,
    dropoff_lat  DOUBLE PRECISION,
    dropoff_lng  DOUBLE PRECISION,
    status       VARCHAR(20)  NOT NULL DEFAULT 'REQUESTED',
    fare         NUMERIC(10,2),
    created_at   TIMESTAMPTZ  DEFAULT NOW()
);

-- Shard rides by user_id: all rides for one user on one shard = fast history queries.
SELECT create_distributed_table('rides', 'user_id');

-- drivers table is small; use reference table (replicated to all shards).
SELECT create_reference_table('drivers');

\q
```

---

## Step 0D: Update MODULE.bazel

Replace the `maven.install` block in the root `MODULE.bazel` with the full dependency list needed by all five services:

```python
# MODULE.bazel (root of monorepo) — replace existing maven block

maven = use_extension("@rules_jvm_external//:extensions.bzl", "maven")
maven.install(
    artifacts = [
        # ── Spring Boot starters ───────────────────────────────────────────
        "org.springframework.boot:spring-boot-starter-web:3.2.3",
        "org.springframework.boot:spring-boot-starter-data-mongodb:3.2.3",
        "org.springframework.boot:spring-boot-starter-data-jpa:3.2.3",
        "org.springframework.boot:spring-boot-starter-data-redis:3.2.3",
        "org.springframework.boot:spring-boot-starter-cache:3.2.3",

        # ── Spring Cloud Gateway (Phase 5 — API Gateway) ──────────────────
        # NOTE: uses Netty (reactive). Do NOT add spring-boot-starter-web
        # to the api-gateway service — they conflict.
        "org.springframework.cloud:spring-cloud-starter-gateway:4.1.2",

        # ── Kafka ──────────────────────────────────────────────────────────
        "org.springframework.kafka:spring-kafka:3.1.2",

        # ── Database drivers ───────────────────────────────────────────────
        "org.postgresql:postgresql:42.7.2",

        # ── Object storage ─────────────────────────────────────────────────
        "com.amazonaws:aws-java-sdk-s3:1.12.670",
        "com.amazonaws:aws-java-sdk-core:1.12.670",

        # ── JSON serialization ─────────────────────────────────────────────
        "com.fasterxml.jackson.core:jackson-databind:2.16.1",

        # ── gRPC (Location Service + Ad Serving Service) ───────────────────
        # net.devh starter provides @GrpcService + @GrpcClient annotations.
        # grpc-netty-shaded: self-contained Netty; avoids version conflicts.
        "net.devh:grpc-spring-boot-starter:2.15.0.RELEASE",
        "io.grpc:grpc-netty-shaded:1.62.2",
        "io.grpc:grpc-protobuf:1.62.2",
        "io.grpc:grpc-stub:1.62.2",
        "com.google.protobuf:protobuf-java:3.25.3",
        "javax.annotation:javax.annotation-api:1.3.2",

        # ── GraphQL (Feed BFF) ─────────────────────────────────────────────
        "org.springframework.boot:spring-boot-starter-graphql:3.2.3",
        "org.springframework.boot:spring-boot-starter-webflux:3.2.3",
        # GraphQL over WebSocket (subscriptions)
        "org.springframework:spring-websocket:6.1.4",
    ],
    repositories = [
        "https://repo1.maven.org/maven2",
    ],
    fetch_sources = True,
)
use_repo(maven, "maven")
```

---

## Step 0E: Run Order

Build and start services in this order (each depends on the previous being healthy):

```bash
# 1. Infrastructure
cd apps/uber && docker compose up -d
# Wait ~30 seconds for Kafka and Postgres to be ready

# 2. Run DDL (Step 0C above)

# 3. Start services (each in a separate terminal)
./bazel-bin/apps/uber/eats-service/eats-service        # :8081
./bazel-bin/apps/uber/ads-service/ads-service          # :8082
./bazel-bin/apps/uber/rides-service/rides-service      # :8083
./bazel-bin/apps/uber/notification-worker/notification-worker  # :8084
./bazel-bin/apps/uber/api-gateway/api-gateway          # :8080
```

All API calls go through the gateway on port 8080.
