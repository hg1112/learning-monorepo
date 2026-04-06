# Uber Mini-Backend

Full-stack local Uber backend: REST, gRPC, WebSocket, GraphQL, Kafka, and an ML-powered ad serving pipeline.

---

## Service Map

| Service | Port | gRPC Port | Language | Role |
|---------|------|-----------|----------|------|
| API Gateway | 8080 | — | Java (Spring Cloud Gateway) | Routing + rate limiting |
| Eats Service | 8081 | — | Java (Spring Boot) | Restaurant catalog, menus, orders |
| Campaign Management | 8082 | — | Java (Spring Boot) | Advertiser CRUD, fixed-bid campaigns, budget |
| Rides Service | 8083 | — | Java (Spring Boot) | Ride lifecycle; calls Location via gRPC |
| Notification Worker | 8084 | — | Java (Spring Boot) | Kafka consumer only, no web layer |
| Feed BFF | 8085 | — | Java (Spring Boot) | GraphQL aggregator (parallel resolvers) |
| Location Service | 8086 | 9090 | Java (Spring Boot) | gRPC stream inbound; WebSocket outbound to rider map |
| Ad Serving Service | 8089 | 9091 | Java (Spring Boot) | 3-stage pipeline: Retrieval → DCN Ranking → Auction |
| ML Platform | 8090 | — | Python (FastAPI) | Feature extraction, Triton client, Qdrant ANN |
| Triton Inference Server | 8000 / 8001 | 8001 | C++ (NVIDIA) | Hosts user_tower + ad_tower + dcn_ranker ONNX models |

---

## Infrastructure

```bash
cd apps/uber && docker compose up -d
```

| Component | Port | Used By |
|-----------|------|---------|
| MongoDB 7 | 27017 | Eats |
| PostgreSQL (Citus) | 5432 | Campaign Mgmt, Rides |
| Redis 7 | 6379 | API Gateway (rate limit), Location (GEO), Campaign Mgmt (budget counters) |
| Kafka (KRaft) | 9092 | All services |
| MinIO | 9000 / 9001 | Eats, Campaign Mgmt |
| Qdrant | 6333 | ML Platform — ad embedding ANN index |
| MLflow | 5001 | ML Platform — model registry + experiment tracking |
| Triton | 8000 (HTTP) / 8001 (gRPC) / 8002 (metrics) | ML Platform client |

---

## Protocol Map

| Call | Protocol | Why Not the Alternative |
|------|----------|------------------------|
| Driver App → Location Service | gRPC bidirectional stream | WebSocket has no binary encoding or flow control. gRPC HTTP/2 = HPACK header compression, backpressure, ~0.5ms vs HTTP 5-10ms. |
| Location Service → Rider App (map) | WebSocket | Browser/mobile native. Server pushes driver positions. gRPC not browser-native without grpc-web proxy. |
| Feed BFF → Ad Serving | gRPC unary | Binary, ~0.5ms. Called on every feed load. JSON over HTTP adds 3-5ms per call. |
| Feed BFF → Location (nearby count) | gRPC unary | Same latency argument. |
| Ad Serving → ML Platform | REST (HTTP) | FastAPI service; internal LAN call ~1ms. Acceptable for orchestration logic. |
| ML Platform → Triton | gRPC (tritonclient) | Triton's native protocol. Binary tensor transfer, dynamic batching. |
| All other service calls | REST | CRUD, admin ops — not latency-sensitive. |
| Async events | Kafka | Decoupling, 7-day replay, multiple independent consumers per topic. |
| External mobile clients | REST + GraphQL | gRPC not browser-native. GraphQL for feed (one query vs 3 REST calls). |

---

## ML-Powered Ad Serving Pipeline

```
Feed Request (userId, lat, lng, cuisine)
    │
    ▼  Ad Serving Service  (Java, gRPC port 9091)
    │
    ├─ Stage 1: RETRIEVAL  ─────────────────────────────────────────────────────────
    │   ML Platform POST /api/ml/retrieve
    │     ├── Extract user features from Redis (recent cuisines, click history, session)
    │     ├── Triton gRPC → user_tower model → 128-dim L2-normalized embedding
    │     └── Qdrant ANN (cosine similarity) → top-100 candidate ad IDs
    │
    ├─ Stage 2: RANKING (DCN v2) ───────────────────────────────────────────────────
    │   ML Platform POST /api/ml/rank
    │     ├── Fetch ad features for 100 candidates from Campaign Mgmt REST
    │     ├── Build feature matrix [100 × 32]
    │     ├── Triton gRPC → dcn_ranker (dynamic batch of 100) → [ctr_score × 100]
    │     └── Circuit breaker: if Triton >15ms, fall back to fixedBid ranking
    │
    └─ Stage 3: AUCTION ────────────────────────────────────────────────────────────
        effectiveCPM = campaign.fixedBidCpm × predictedCTR
        Second-price winner (pays second-highest effectiveCPM + $0.01)
        Publish to Kafka ad-impression-events
        Atomic Redis budget deduction (Lua script)
```

---

## DCN v2 Ranker Architecture

```
Input features (dim = 32):
  Dense (13):  bid_amount, historical_ctr, cuisine_match_score,
               time_sin, time_cos, day_sin, day_cos, user_order_count,
               ad_age_days, distance_km, budget_remaining_ratio,
               quality_score, user_session_length
  Sparse embeddings (19):
               user_id → 8-dim | cuisine_id → 8-dim | hour_of_day → 3-dim

Cross Network (3 layers):
  x_{l+1} = x_0 ⊙ (W_l · x_l + b_l) + x_l
  Learns bounded-degree feature interactions explicitly.
  "Italian cuisine × lunchtime" interaction = one cross layer weight matrix.
  No manual feature engineering needed.

Deep Network (256 → 128 → 64):
  Standard MLP for implicit higher-order interactions.

Output: concat(cross_out, deep_out) → Linear(1) → Sigmoid → P(click)
Training: binary cross-entropy on impression (y=0) + click (y=1) labels
Export: torch.onnx.export() → Triton ONNX runtime backend
```

---

## Two-Tower Retrieval

```
User Tower: MLP(user_features[20]) → LayerNorm → 128-dim unit vector   [online, Triton]
Ad Tower:   MLP(ad_features[16])   → LayerNorm → 128-dim unit vector   [offline, Qdrant]

Training: SimCLR-style in-batch negatives. Loss = InfoNCE.
  Positive pairs: (user, ad they clicked)
  Negatives: other ads in the same batch

Deployment:
  Ad Tower: runs offline when new ad is created (triggered by Kafka ad-created-events)
            ad embedding stored/updated in Qdrant
  User Tower: runs online per request via Triton
  ANN: Qdrant cosine similarity top-100, <5ms p99
```

---

## Campaign Management — Fixed Bid Model

```
Advertiser
  └── Campaign
        ├── fixedBidCpm: double      ← Advertiser's fixed bid per 1000 impressions
        ├── dailyBudget: double      ← Hard cap. Enforced via Redis atomic Lua.
        ├── totalBudget: double
        ├── targetCuisines: []       ← Feed into Qdrant filter
        ├── targetRadiusKm: double
        ├── schedule: start/end date
        └── status: DRAFT | ACTIVE | PAUSED | PAUSED_BUDGET | ENDED
              └── Ad (creative)
                    ├── title, description, imageUrl
                    └── On create → Kafka ad-created-events
                                  → ML Platform computes ad_tower embedding
                                  → Qdrant upsert

Budget enforcement (Redis atomic Lua):
  KEY: campaign:{id}:spend_today
  On win: INCR by clearingPrice. If result > dailyBudget → campaign status = PAUSED_BUDGET.
  Midnight reset: scheduled job flushes all spend counters, restores PAUSED_BUDGET → ACTIVE.
```

---

## Location Service — gRPC + WebSocket

```
Driver App                          Location Service (8086, gRPC 9090)
  │                                        │
  ├── gRPC BiDi stream ──────────────────► │ StreamLocation(stream LocationUpdate)
  │   LocationUpdate{driverId,lng,lat}     │   → GEOADD drivers:locations lng lat driverId
  │                                        │   → Kafka location-events
  │ ◄─────────────── RideAssignment ───── │   → If driver matched: push RideAssignment back
  │

Rider App                           Location Service
  │                                        │
  ├── WebSocket /ws/drivers ────────────► │ Subscribes to nearby drivers
  │                                        │   → Poll Redis GEORADIUS every 2s
  │ ◄─────── {driverId, lat, lng} ─────── │   → Push driver positions to rider
```

---

## GraphQL Feed BFF (8085)

```graphql
type Query {
  feed(latitude: Float!, longitude: Float!, cuisine: String): Feed
  restaurant(id: ID!): Restaurant
  order(id: ID!): Order
  myRides(userId: ID!): [Ride]
}

type Mutation {
  placeOrder(input: OrderInput!): Order
  requestRide(input: RideInput!): Ride
}

type Feed {
  restaurants: [Restaurant]
  featuredAd: Ad
  nearbyDriverCount: Int
}
```

Resolvers run in parallel:
- `restaurants` → REST → Eats Service
- `featuredAd`  → gRPC → Ad Serving Service (full pipeline: retrieval → ranking → auction)
- `nearbyDriverCount` → gRPC → Location Service

---

## Kafka Topics

| Topic | Producer | Consumer(s) |
|-------|----------|-------------|
| `order-events` | Eats | Notification Worker |
| `ride-events` | Rides | Notification Worker |
| `location-events` | Location | (analytics pipeline) |
| `ad-impression-events` | Ad Serving | Notification Worker, ML Platform (training labels) |
| `ad-click-events` | Feed BFF | ML Platform (training labels) |
| `ad-created-events` | Campaign Mgmt | ML Platform (triggers ad embedding → Qdrant) |

---

## Triton Model Repository

```
apps/uber/ml-platform/model_repository/
├── user_tower/        config.pbtxt + 1/model.onnx   ← online, per request
├── ad_tower/          config.pbtxt + 1/model.onnx   ← offline, on ad creation
└── dcn_ranker/        config.pbtxt + 1/model.onnx   ← online, batch of 100
```

`--model-control-mode=poll` (30s interval): new ONNX model versions hot-loaded without Triton restart.

---

## Bazel Commands

```bash
# Install
which bazel || npm install -g @bazel/bazelisk && alias bazel=bazelisk

# Build Java services
bazel build //apps/uber/eats-service:eats-service
bazel build //apps/uber/campaign-management:campaign-management
bazel build //apps/uber/rides-service:rides-service
bazel build //apps/uber/notification-worker:notification-worker
bazel build //apps/uber/location-service:location-service
bazel build //apps/uber/ad-serving-service:ad-serving-service
bazel build //apps/uber/feed-bff:feed-bff
bazel build //apps/uber/api-gateway:api-gateway

# ML Platform — plain Python, no Bazel needed
cd apps/uber/ml-platform
pip install -r requirements.txt
uvicorn main:app --host 0.0.0.0 --port 8090 --reload
```

---

## Implementation Guide

| File | Contents |
|------|----------|
| `STEPS.md` | Bazel setup, Docker Compose (full infra), DDL, MODULE.bazel |
| `STEPS_1.md` | Phase 1 — Eats Service (unchanged from original) |
| `STEPS_2.md` | Phase 2 — Campaign Management (fixed-bid, budget tracking, ad-created-events) |
| `STEPS_3.md` | Phase 3 — Rides Service (calls Location via gRPC, stripped of GEO logic) |
| `STEPS_4.md` | Phase 4 — Notification Worker + API Gateway (rate limiter) |
| `STEPS_5.md` | Phase 5 — Location Service (gRPC bidirectional stream + WebSocket push) |
| `STEPS_6.md` | Phase 6 — Ad Serving Service (3-stage pipeline, gRPC server, circuit breaker) |
| `STEPS_7.md` | Phase 7 — ML Platform Python (Two-Tower, DCN v2, Triton client, Qdrant, MLflow) |
| `STEPS_8.md` | Phase 8 — Triton Model Configs + ONNX export scripts |
| `STEPS_9.md` | Phase 9 — Feed BFF (GraphQL, parallel resolvers, WebSocket subscription) |
