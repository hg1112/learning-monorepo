#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# Uber Mini-Backend — Full Infrastructure Bootstrap
#
# Run once after cloning. Safe to re-run: all commands use --if-not-exists
# or equivalent idempotent flags.
#
# Usage:
#   cd apps/uber
#   chmod +x setup.sh
#   ./setup.sh
# ─────────────────────────────────────────────────────────────────────────────

set -euo pipefail
cd "$(dirname "$0")"

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

log()  { echo -e "${GREEN}[setup]${NC} $*"; }
warn() { echo -e "${YELLOW}[setup]${NC} $*"; }

# ── 1. Start all infrastructure ───────────────────────────────────────────────
log "Starting Docker Compose..."
docker compose up -d

# ── 2. Wait for PostgreSQL ────────────────────────────────────────────────────
log "Waiting for PostgreSQL..."
until docker exec uber-postgres pg_isready -U uber -d uber -q 2>/dev/null; do
    sleep 1
done
log "PostgreSQL ready."

# ── 3. Wait for Kafka ─────────────────────────────────────────────────────────
log "Waiting for Kafka..."
until docker exec uber-kafka kafka-topics.sh \
        --bootstrap-server localhost:9092 --list &>/dev/null; do
    sleep 2
done
log "Kafka ready."

# ── 4. Wait for Redis ─────────────────────────────────────────────────────────
log "Waiting for Redis..."
until docker exec uber-redis redis-cli ping 2>/dev/null | grep -q PONG; do
    sleep 1
done
log "Redis ready."

# ── 5. Wait for MinIO ─────────────────────────────────────────────────────────
log "Waiting for MinIO..."
until curl -sf http://localhost:9000/minio/health/live &>/dev/null; do
    sleep 1
done
log "MinIO ready."

# ── 6. Wait for Qdrant ───────────────────────────────────────────────────────
log "Waiting for Qdrant..."
until curl -sf http://localhost:6333/healthz &>/dev/null; do
    sleep 1
done
log "Qdrant ready."

# ─────────────────────────────────────────────────────────────────────────────
# PostgreSQL DDL
# ─────────────────────────────────────────────────────────────────────────────
log "Creating PostgreSQL schema..."

docker exec -i uber-postgres psql -U uber -d uber << 'SQL'

-- ── Advertisers ───────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS advertisers (
    id      BIGSERIAL PRIMARY KEY,
    name    VARCHAR(255) NOT NULL,
    email   VARCHAR(255) UNIQUE
);

-- ── Campaigns ─────────────────────────────────────────────────────────────────
-- Sharded by advertiser_id so all campaigns from one advertiser live on one shard.
-- Cross-shard JOINs between campaigns and rides are never needed.
CREATE TABLE IF NOT EXISTS campaigns (
    id                BIGSERIAL PRIMARY KEY,
    advertiser_id     BIGINT        NOT NULL,
    name              VARCHAR(255)  NOT NULL,
    fixed_bid_cpm     NUMERIC(10,4) NOT NULL,
    daily_budget      NUMERIC(10,2) NOT NULL,
    total_budget      NUMERIC(10,2),
    target_cuisines   TEXT,
    target_radius_km  DOUBLE PRECISION DEFAULT 10.0,
    status            VARCHAR(20)   NOT NULL DEFAULT 'DRAFT',
    start_date        TIMESTAMPTZ,
    end_date          TIMESTAMPTZ,
    created_at        TIMESTAMPTZ   DEFAULT NOW()
);

-- ── Ads (creatives) ───────────────────────────────────────────────────────────
-- Same shard key as campaigns (advertiser_id) so JOINs stay on-shard.
CREATE TABLE IF NOT EXISTS ads (
    id            BIGSERIAL PRIMARY KEY,
    advertiser_id BIGINT        NOT NULL,
    campaign_id   BIGINT        NOT NULL,
    title         VARCHAR(255)  NOT NULL,
    description   TEXT,
    image_url     TEXT,
    status        VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
    created_at    TIMESTAMPTZ   DEFAULT NOW()
);

-- ── Rides ─────────────────────────────────────────────────────────────────────
-- Sharded by user_id: all rides for one user on one shard = fast history queries.
CREATE TABLE IF NOT EXISTS rides (
    id           BIGSERIAL PRIMARY KEY,
    user_id      BIGINT           NOT NULL,
    driver_id    BIGINT,
    pickup_lat   DOUBLE PRECISION NOT NULL,
    pickup_lng   DOUBLE PRECISION NOT NULL,
    dropoff_lat  DOUBLE PRECISION,
    dropoff_lng  DOUBLE PRECISION,
    status       VARCHAR(20)      NOT NULL DEFAULT 'REQUESTED',
    fare         NUMERIC(10,2),
    created_at   TIMESTAMPTZ      DEFAULT NOW()
);

-- ── Drivers ───────────────────────────────────────────────────────────────────
-- Small table — replicated to all Citus shards as a reference table.
-- This allows JOIN with rides without cross-shard network hops.
CREATE TABLE IF NOT EXISTS drivers (
    id            BIGSERIAL PRIMARY KEY,
    name          VARCHAR(255) NOT NULL,
    vehicle_plate VARCHAR(20),
    status        VARCHAR(20)  NOT NULL DEFAULT 'AVAILABLE'
);

-- ── Citus distribution ────────────────────────────────────────────────────────
-- Only run if Citus extension is available (it is in citusdata/citus image).
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'citus') THEN
        -- Distribute tables by shard key
        PERFORM create_distributed_table('campaigns', 'advertiser_id');
        PERFORM create_distributed_table('ads',       'advertiser_id');
        PERFORM create_distributed_table('rides',     'user_id');
        -- Replicate small lookup tables to all shards
        PERFORM create_reference_table('drivers');
        PERFORM create_reference_table('advertisers');
        RAISE NOTICE 'Citus sharding configured.';
    ELSE
        RAISE NOTICE 'Citus not installed — running plain PostgreSQL.';
    END IF;
EXCEPTION
    WHEN OTHERS THEN
        RAISE NOTICE 'Citus setup skipped (tables may already be distributed): %', SQLERRM;
END
$$;

SQL

log "PostgreSQL schema ready."

# ─────────────────────────────────────────────────────────────────────────────
# Kafka Topics
# ─────────────────────────────────────────────────────────────────────────────
log "Creating Kafka topics..."

# 6 partitions: allows 6 parallel consumers per consumer group.
# For location-events, use more partitions if driver count > 100K.
TOPICS=(
    "order-events"           # Eats → Notification Worker
    "ride-events"            # Rides → Notification Worker
    "location-events"        # Location Service → analytics
    "ad-impression-events"   # Ad Serving → Notification Worker, ML Platform (labels)
    "ad-click-events"        # Feed BFF → ML Platform (labels)
    "ad-created-events"      # Campaign Mgmt → ML Platform (triggers ad embedding)
    "ad-write-behind"        # WriteBehind strategy → Campaign Mgmt Kafka consumer
)

for topic in "${TOPICS[@]}"; do
    docker exec uber-kafka kafka-topics.sh \
        --bootstrap-server localhost:9092 \
        --create \
        --if-not-exists \
        --topic "$topic" \
        --partitions 6 \
        --replication-factor 1
    log "  topic: $topic"
done

log "Kafka topics ready."

# ─────────────────────────────────────────────────────────────────────────────
# MinIO Buckets
# ─────────────────────────────────────────────────────────────────────────────
log "Creating MinIO buckets..."

# mc (MinIO Client) is bundled in the MinIO container image
docker exec uber-minio sh -c "
    mc alias set local http://localhost:9000 minioadmin minioadmin --api s3v4 2>/dev/null
    mc mb --ignore-existing local/eats-images
    mc mb --ignore-existing local/ads-images
    # Set public read policy so images can be fetched directly by mobile clients
    mc anonymous set download local/eats-images
    mc anonymous set download local/ads-images
"

log "MinIO buckets ready."

# ─────────────────────────────────────────────────────────────────────────────
# Qdrant Collection — ad_embeddings
# ─────────────────────────────────────────────────────────────────────────────
log "Creating Qdrant collection..."

# 128-dim L2-normalized vectors from ad_tower model.
# Cosine similarity = dot product on unit vectors (same ranking, cheaper math).
# HNSW index: ef_construct=200 for high recall during initial build.
curl -s -X PUT http://localhost:6333/collections/ad_embeddings \
    -H "Content-Type: application/json" \
    -d '{
        "vectors": {
            "size": 128,
            "distance": "Cosine"
        },
        "hnsw_config": {
            "m": 16,
            "ef_construct": 200
        }
    }' | grep -q '"result":true' && log "Qdrant collection ready." \
        || warn "Qdrant collection may already exist (OK)."

# ─────────────────────────────────────────────────────────────────────────────
# MLflow — no setup needed (SQLite backend auto-inits on first run)
# ─────────────────────────────────────────────────────────────────────────────

# ─────────────────────────────────────────────────────────────────────────────
# Triton — model repository directory
# ─────────────────────────────────────────────────────────────────────────────
log "Creating Triton model repository directories..."
mkdir -p ml-platform/model_repository/{user_tower,ad_tower,dcn_ranker}/1
log "  Run STEPS_8.md to export ONNX models into these directories."

# ─────────────────────────────────────────────────────────────────────────────
# Summary
# ─────────────────────────────────────────────────────────────────────────────
echo ""
log "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
log "Infrastructure ready. Service start order:"
log ""
log "  1. bazel build //apps/uber/eats-service:eats-service        # :8081"
log "  2. bazel build //apps/uber/campaign-management:campaign-management  # :8082"
log "  3. bazel build //apps/uber/rides-service:rides-service      # :8083"
log "  4. bazel build //apps/uber/notification-worker:notification-worker  # :8084"
log "  5. bazel build //apps/uber/location-service:location-service # :8086"
log "  6. bazel build //apps/uber/ad-serving-service:ad-serving-service    # :8089"
log "  7. cd ml-platform && uvicorn main:app --port 8090 --reload"
log "  8. bazel build //apps/uber/feed-bff:feed-bff                # :8085"
log "  9. bazel build //apps/uber/api-gateway:api-gateway          # :8080"
log ""
log "  MinIO console: http://localhost:9001  (minioadmin / minioadmin)"
log "  MLflow UI:     http://localhost:5001"
log "  Qdrant UI:     http://localhost:6333/dashboard"
log "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
