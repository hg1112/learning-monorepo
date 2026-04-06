# Phase 7 — ML Platform (Port 8090, Python)

The ML Platform is a Python FastAPI service. It owns all machine learning logic: feature extraction, model inference via Triton, embedding management in Qdrant, and experiment tracking in MLflow.

**Why Python, not Java?**
PyTorch, ONNX, Triton client, Qdrant client, and MLflow are first-class Python libraries. The Java ML ecosystem is thin. The ML Platform is not latency-critical for individual calls (Ad Serving has already set a 15ms circuit breaker) — it's throughput-critical. FastAPI with async handlers handles thousands of concurrent requests efficiently.

**No Bazel for this service.** Run directly with `uvicorn`.

---

## Two-Tower Architecture (Retrieval)

```
User Tower:  MLP([user_features × 20]) → BatchNorm → 128-dim unit vector  [online, Triton]
Ad Tower:    MLP([ad_features  × 16])  → BatchNorm → 128-dim unit vector  [offline, Qdrant]

Training (SimCLR-style in-batch negatives):
  Positive pair: (user, ad they clicked)
  Negatives:     all other ads in the same batch (no separate negative sampling)
  Loss:          InfoNCE = -log(exp(sim(u,a+)/τ) / Σ exp(sim(u,aᵢ)/τ))
  τ = temperature (0.07): sharpens the similarity distribution

Deployment split:
  Ad Tower: runs OFFLINE when new ad is created (Kafka ad-created-events consumer)
            → embedding stored in Qdrant
  User Tower: runs ONLINE per request via Triton
  ANN query:  Qdrant cosine similarity top-100, <5ms P99
```

## DCN v2 Architecture (Ranking)

```
Input features (32-dim):
  Dense (13):  fixedBidCpm, historicalCtr, cuisineMatchScore, time_sin, time_cos,
               day_sin, day_cos, userOrderCount, adAgeDays, distanceKm,
               budgetRemainingRatio, qualityScore, userSessionLength
  Sparse embeddings (19):
               userId     → 8-dim embedding
               cuisineId  → 8-dim embedding
               hourOfDay  → 3-dim embedding

Cross Network (3 layers):
  x_{l+1} = x_0 ⊙ (W_l · x_l + b_l) + x_l
  Learns bounded-degree feature interactions explicitly.
  "Italian cuisine × lunchtime" = one cross layer learns this interaction.

Deep Network: Linear(32→256) → ReLU → Linear(256→128) → ReLU → Linear(128→64)

Output: concat(cross_out, deep_out) → Linear(1) → Sigmoid → P(click)
Loss:   BCELoss on impression (y=0) + click (y=1) labels from Kafka
Export: torch.onnx.export() → Triton ONNX runtime (see STEPS_8)
```

---

## Directory Structure

```
apps/uber/ml-platform/
├── requirements.txt
├── main.py                 ← FastAPI app, all routes
├── feature_store.py        ← Redis-backed user feature extraction
├── triton_client.py        ← gRPC client for Triton inference server
├── qdrant_client_wrapper.py ← ANN search + embedding upsert
├── models/
│   ├── two_tower.py        ← PyTorch Two-Tower definition + training
│   └── dcn_ranker.py       ← PyTorch DCN v2 definition + training
└── model_repository/       ← Triton model configs (populated in STEPS_8)
    ├── user_tower/
    ├── ad_tower/
    └── dcn_ranker/
```

---

## File 1: requirements.txt

```
# apps/uber/ml-platform/requirements.txt
fastapi==0.110.0
uvicorn[standard]==0.29.0
redis==5.0.3
qdrant-client==1.9.1
tritonclient[grpc]==2.44.0
mlflow==2.12.1
torch==2.2.2
onnx==1.16.0
numpy==1.26.4
pydantic==2.7.0
httpx==0.27.0
```

---

## File 2: feature_store.py

```python
# apps/uber/ml-platform/feature_store.py
"""
Redis-backed feature store for real-time user features.

Keys:
  user:{id}:features  →  Hash of feature values
  user:{id}:history   →  List of last N clicked ad IDs (LPUSH + LTRIM)

In production, use Feast or Tecton as a proper feature store:
- They handle offline/online consistency (training-serving skew)
- Materialization jobs sync features from data warehouse to Redis
- Point-in-time correct feature retrieval for training
"""
import json
import redis
import numpy as np
from typing import Optional

r = redis.Redis(host="localhost", port=6379, decode_responses=True)


def get_user_features(user_id: str) -> np.ndarray:
    """
    Returns a 20-dim float32 feature vector for the user tower.
    Features: [recent_cuisine_embeddings(8), click_rate(1), order_count(1),
               session_length(1), time_sin(1), time_cos(1), day_sin(1), day_cos(1),
               lat_norm(1), lng_norm(1), avg_spend(1), app_version_enc(2)]
    """
    features = r.hgetall(f"user:{user_id}:features")
    if not features:
        # Cold start: return zero vector. User tower will produce a generic embedding.
        return np.zeros(20, dtype=np.float32)

    import time, math
    now = time.time()
    hour = (now % 86400) / 3600   # 0-24
    day  = (now % (86400 * 7)) / 86400  # 0-7

    vec = np.array([
        float(features.get("cuisine_embed_0", 0)),
        float(features.get("cuisine_embed_1", 0)),
        float(features.get("cuisine_embed_2", 0)),
        float(features.get("cuisine_embed_3", 0)),
        float(features.get("cuisine_embed_4", 0)),
        float(features.get("cuisine_embed_5", 0)),
        float(features.get("cuisine_embed_6", 0)),
        float(features.get("cuisine_embed_7", 0)),
        float(features.get("click_rate", 0)),
        float(features.get("order_count", 0)) / 100.0,  # normalize
        float(features.get("session_length", 0)) / 3600.0,
        math.sin(2 * math.pi * hour / 24),
        math.cos(2 * math.pi * hour / 24),
        math.sin(2 * math.pi * day / 7),
        math.cos(2 * math.pi * day / 7),
        float(features.get("lat_norm", 0)),
        float(features.get("lng_norm", 0)),
        float(features.get("avg_spend", 0)) / 50.0,  # normalize to ~0-1
        float(features.get("app_version", 0)) / 10.0,
        0.0,  # placeholder
    ], dtype=np.float32)

    return vec


def get_ad_features(ad_id: int) -> np.ndarray:
    """
    Returns a 16-dim float32 feature vector for the ad tower.
    Features: [bid_cpm, historical_ctr, cuisine_embed(8), ad_age_days,
               image_quality_score, title_len_norm, target_radius_norm, budget_ratio, status_enc]
    """
    features = r.hgetall(f"ad:{ad_id}:features")
    if not features:
        return np.zeros(16, dtype=np.float32)

    import time
    created_at = float(features.get("created_at", time.time()))
    age_days = (time.time() - created_at) / 86400

    return np.array([
        float(features.get("bid_cpm", 1.0)) / 10.0,
        float(features.get("historical_ctr", 0.05)),
        float(features.get("cuisine_embed_0", 0)),
        float(features.get("cuisine_embed_1", 0)),
        float(features.get("cuisine_embed_2", 0)),
        float(features.get("cuisine_embed_3", 0)),
        float(features.get("cuisine_embed_4", 0)),
        float(features.get("cuisine_embed_5", 0)),
        float(features.get("cuisine_embed_6", 0)),
        float(features.get("cuisine_embed_7", 0)),
        min(age_days / 30.0, 1.0),  # normalize to 0-1 (cap at 30 days)
        float(features.get("image_quality_score", 0.5)),
        float(features.get("title_length", 20)) / 50.0,
        float(features.get("target_radius_km", 10)) / 50.0,
        float(features.get("budget_remaining_ratio", 1.0)),
        1.0 if features.get("status") == "ACTIVE" else 0.0,
    ], dtype=np.float32)


def record_click(user_id: str, ad_id: int):
    """Update user click history (used as training labels)."""
    r.lpush(f"user:{user_id}:history", ad_id)
    r.ltrim(f"user:{user_id}:history", 0, 99)  # keep last 100
```

---

## File 3: triton_client.py

```python
# apps/uber/ml-platform/triton_client.py
"""
gRPC client for NVIDIA Triton Inference Server.

Why gRPC for Triton (not HTTP)?
- Binary tensor serialization: no JSON parsing overhead
- HTTP Triton: ~3ms overhead. gRPC Triton: ~0.5ms overhead.
- Dynamic batching: Triton batches concurrent requests transparently.
  With gRPC, multiple inference calls share one HTTP/2 connection.
- At 1M feed loads/sec, each needing user_tower inference: gRPC saves
  (3ms - 0.5ms) × 1M = 2,500 server-seconds per second. Significant.
"""
import numpy as np
import tritonclient.grpc as grpcclient
from typing import List


_client = grpcclient.InferenceServerClient(url="localhost:8001", verbose=False)


def infer_user_tower(user_features: np.ndarray) -> np.ndarray:
    """
    Run user_tower model. Returns 128-dim L2-normalized embedding.
    Input:  [1, 20] float32
    Output: [1, 128] float32 (unit vector)
    """
    inp = grpcclient.InferInput("input", [1, 20], "FP32")
    inp.set_data_from_numpy(user_features.reshape(1, 20))

    out = grpcclient.InferRequestedOutput("output")
    response = _client.infer(model_name="user_tower", inputs=[inp], outputs=[out])
    embedding = response.as_numpy("output")[0]   # shape: [128]

    # L2 normalize (Triton may or may not do this depending on model export)
    norm = np.linalg.norm(embedding)
    return embedding / (norm + 1e-9)


def infer_ad_tower(ad_features: np.ndarray) -> np.ndarray:
    """
    Run ad_tower model. Returns 128-dim L2-normalized embedding.
    Input:  [1, 16] float32
    Output: [1, 128] float32
    """
    inp = grpcclient.InferInput("input", [1, 16], "FP32")
    inp.set_data_from_numpy(ad_features.reshape(1, 16))

    out = grpcclient.InferRequestedOutput("output")
    response = _client.infer(model_name="ad_tower", inputs=[inp], outputs=[out])
    embedding = response.as_numpy("output")[0]

    norm = np.linalg.norm(embedding)
    return embedding / (norm + 1e-9)


def infer_dcn_ranker(feature_matrix: np.ndarray) -> np.ndarray:
    """
    Run dcn_ranker on a batch of N candidates.
    Input:  [N, 32] float32
    Output: [N, 1] float32 (predicted CTR per candidate)

    Dynamic batching: Triton can merge concurrent calls into one GPU kernel.
    With dcn_ranker config max_batch_size=256, Triton batches all concurrent
    requests until the batch is full or a 5ms timeout fires.
    """
    n = feature_matrix.shape[0]
    inp = grpcclient.InferInput("input", [n, 32], "FP32")
    inp.set_data_from_numpy(feature_matrix.astype(np.float32))

    out = grpcclient.InferRequestedOutput("output")
    response = _client.infer(model_name="dcn_ranker", inputs=[inp], outputs=[out])
    return response.as_numpy("output").flatten()   # shape: [N]
```

---

## File 4: qdrant_client_wrapper.py

```python
# apps/uber/ml-platform/qdrant_client_wrapper.py
"""
Qdrant vector database wrapper.

Collection: ad_embeddings
  Vector: 128-dim float32, Cosine similarity
  Payload: {"ad_id": int, "campaign_id": int, "cuisines": [str], "status": str}

Why Qdrant (not Faiss or Pinecone)?
- Faiss: in-process only, no persistence, no filtered search.
- Pinecone: managed, $$$, no local dev.
- Qdrant: self-hosted, payload filtering (cuisine/status filter during ANN),
          gRPC or REST, HNSW index with ef_construct tuning.

HNSW parameters:
  m=16:           16 bidirectional links per node in graph.
                  Higher m = better recall, more memory (m×2 links per node).
  ef_construct=200: graph quality at build time. Higher = slower index, better recall.
  ef=100 at query: search expansion factor. Higher = better recall, slower query.
                   At 100K ad embeddings, ef=100 gives ~98% recall at <5ms.
"""
from qdrant_client import QdrantClient
from qdrant_client.models import PointStruct, Filter, FieldCondition, MatchValue
from typing import List, Optional
import numpy as np

_client = QdrantClient(host="localhost", port=6333)
COLLECTION = "ad_embeddings"


def upsert_ad_embedding(ad_id: int, campaign_id: int,
                         cuisines: List[str], embedding: np.ndarray):
    """Store or update an ad's tower embedding in Qdrant."""
    _client.upsert(
        collection_name=COLLECTION,
        points=[PointStruct(
            id=ad_id,
            vector=embedding.tolist(),
            payload={
                "ad_id": ad_id,
                "campaign_id": campaign_id,
                "cuisines": cuisines,
                "status": "ACTIVE",
            }
        )]
    )


def search_similar_ads(user_embedding: np.ndarray, cuisine: str,
                        top_k: int = 100) -> List[int]:
    """
    ANN search: find top-k ads similar to user embedding.
    Filters to ACTIVE ads matching the cuisine context.

    Payload filtering happens inside the HNSW graph (not post-filter),
    so filtered ANN has similar latency to unfiltered ANN.
    """
    cuisine_filter = Filter(
        must=[
            FieldCondition(key="status", match=MatchValue(value="ACTIVE")),
            FieldCondition(key="cuisines", match=MatchValue(value=cuisine)),
        ]
    ) if cuisine else Filter(
        must=[FieldCondition(key="status", match=MatchValue(value="ACTIVE"))]
    )

    results = _client.search(
        collection_name=COLLECTION,
        query_vector=user_embedding.tolist(),
        query_filter=cuisine_filter,
        limit=top_k,
        with_payload=False,   # we only need IDs here
    )

    return [int(r.id) for r in results]
```

---

## File 5: main.py

```python
# apps/uber/ml-platform/main.py
"""
FastAPI service — entry point for all ML Platform endpoints.

Endpoints:
  POST /api/ml/retrieve   Stage 1: user embedding → Qdrant ANN → candidate ad IDs
  POST /api/ml/rank       Stage 2: build feature matrix → dcn_ranker → CTR scores
  POST /api/ml/embed-ad   Offline: compute ad_tower embedding → Qdrant upsert
                          (triggered by ad-created-events Kafka consumer)

Kafka consumer runs in a background thread (not an endpoint).
MLflow experiment tracking is embedded in the training scripts (models/).
"""
import asyncio
import threading
import json
import numpy as np
import mlflow

from fastapi import FastAPI, BackgroundTasks
from pydantic import BaseModel
from typing import List, Dict, Optional

from feature_store import get_user_features, get_ad_features
from triton_client import infer_user_tower, infer_ad_tower, infer_dcn_ranker
from qdrant_client_wrapper import upsert_ad_embedding, search_similar_ads

app = FastAPI(title="Uber ML Platform")

mlflow.set_tracking_uri("http://localhost:5001")
mlflow.set_experiment("ad_serving")


# ── Request / Response models ─────────────────────────────────────────────────

class RetrieveRequest(BaseModel):
    user_id: str
    latitude: float
    longitude: float
    cuisine: str

class RetrieveResponse(BaseModel):
    candidate_ad_ids: List[int]
    user_embedding_norm: float   # sanity check: should be ~1.0

class RankRequest(BaseModel):
    ad_ids: List[int]
    user_id: str
    cuisine: str

class RankResponse(BaseModel):
    rankings: Dict[str, float]   # adId (str) → predictedCTR

class EmbedAdRequest(BaseModel):
    ad_id: int
    campaign_id: int
    cuisines: List[str]


# ── Stage 1: Retrieval ────────────────────────────────────────────────────────

@app.post("/api/ml/retrieve", response_model=RetrieveResponse)
async def retrieve(req: RetrieveRequest):
    """
    1. Extract 20-dim user features from Redis feature store
    2. Run user_tower via Triton gRPC → 128-dim unit vector
    3. ANN search in Qdrant (filtered by cuisine) → top-100 ad IDs

    Typical latency breakdown:
      Feature extraction: ~0.2ms (Redis hash lookup)
      Triton user_tower:  ~2ms   (ONNX inference, CPU)
      Qdrant ANN:         ~3ms   (HNSW ef=100, 100K vectors)
      Total:              ~5ms
    """
    # Run in executor to avoid blocking the event loop on numpy/gRPC
    loop = asyncio.get_event_loop()

    user_features = await loop.run_in_executor(
        None, get_user_features, req.user_id)

    user_embedding = await loop.run_in_executor(
        None, infer_user_tower, user_features)

    candidate_ids = await loop.run_in_executor(
        None, search_similar_ads, user_embedding, req.cuisine, 100)

    return RetrieveResponse(
        candidate_ad_ids=candidate_ids,
        user_embedding_norm=float(np.linalg.norm(user_embedding))
    )


# ── Stage 2: Ranking ──────────────────────────────────────────────────────────

@app.post("/api/ml/rank", response_model=RankResponse)
async def rank(req: RankRequest):
    """
    1. Fetch ad features from Redis for each candidate
    2. Build [N × 32] feature matrix (dense + sparse embeddings)
    3. Run dcn_ranker via Triton (batched) → [N] CTR scores

    Triton dynamic batching: concurrent requests are merged transparently.
    With 100 candidates, the feature matrix is 100×32×4 bytes = 12.8KB — trivial.

    Typical latency:
      Feature fetch: ~1ms (N Redis calls, pipelining possible)
      Triton DCN:    ~5ms (batch of 100, ONNX CPU)
      Total:         ~6ms
    """
    loop = asyncio.get_event_loop()

    # Fetch ad features for all candidates
    ad_features = await loop.run_in_executor(
        None,
        lambda: [get_ad_features(ad_id) for ad_id in req.ad_ids]
    )

    if not ad_features:
        return RankResponse(rankings={})

    feature_matrix = np.stack(ad_features)   # shape: [N, 16]

    # Pad to 32 dims (dense 16 + sparse embeddings 16 approximated as zeros for now)
    # In production: look up user_id embed + cuisine_id embed + hour_of_day embed
    padding = np.zeros((feature_matrix.shape[0], 16), dtype=np.float32)
    full_matrix = np.concatenate([feature_matrix, padding], axis=1)  # [N, 32]

    ctr_scores = await loop.run_in_executor(
        None, infer_dcn_ranker, full_matrix)

    rankings = {str(ad_id): float(score)
                for ad_id, score in zip(req.ad_ids, ctr_scores)}

    return RankResponse(rankings=rankings)


# ── Offline: Ad Embedding ─────────────────────────────────────────────────────

@app.post("/api/ml/embed-ad", status_code=202)
async def embed_ad(req: EmbedAdRequest, background_tasks: BackgroundTasks):
    """
    Compute ad_tower embedding and upsert into Qdrant.
    Called by the Kafka consumer (ad-created-events) as a fire-and-forget.
    Returns 202 immediately; actual embedding happens in background.
    """
    background_tasks.add_task(_compute_and_store_embedding,
                               req.ad_id, req.campaign_id, req.cuisines)
    return {"status": "embedding scheduled", "ad_id": req.ad_id}


def _compute_and_store_embedding(ad_id: int, campaign_id: int, cuisines: List[str]):
    ad_features = get_ad_features(ad_id)
    embedding = infer_ad_tower(ad_features)
    upsert_ad_embedding(ad_id, campaign_id, cuisines, embedding)

    # Track in MLflow for offline analysis
    with mlflow.start_run(run_name=f"embed_ad_{ad_id}"):
        mlflow.log_param("ad_id", ad_id)
        mlflow.log_param("campaign_id", campaign_id)
        mlflow.log_metric("embedding_norm", float(np.linalg.norm(embedding)))


# ── Kafka Consumer (background thread) ───────────────────────────────────────
# Consumes ad-created-events and triggers ad embedding.
# Runs as a daemon thread so it doesn't block uvicorn shutdown.

def _kafka_consumer_loop():
    """
    Consumes ad-created-events. For each new ad, calls /api/ml/embed-ad.
    In production: use Faust or aiokafka for async consumption.
    """
    from kafka import KafkaConsumer
    import httpx

    consumer = KafkaConsumer(
        "ad-created-events",
        bootstrap_servers="localhost:9092",
        group_id="ml-platform-embed",
        auto_offset_reset="earliest",
        value_deserializer=lambda v: json.loads(v.decode("utf-8")),
    )

    client = httpx.Client(base_url="http://localhost:8090", timeout=30)

    for message in consumer:
        event = message.value
        try:
            client.post("/api/ml/embed-ad", json={
                "ad_id": event["adId"],
                "campaign_id": event["campaignId"],
                "cuisines": event.get("targetCuisines", "").split(","),
            })
        except Exception as e:
            print(f"[ml-platform] Failed to embed ad {event.get('adId')}: {e}")


@app.on_event("startup")
def start_kafka_consumer():
    t = threading.Thread(target=_kafka_consumer_loop, daemon=True)
    t.start()


# ── Health ────────────────────────────────────────────────────────────────────

@app.get("/health")
def health():
    return {"status": "ok"}
```

---

## File 6: models/two_tower.py

```python
# apps/uber/ml-platform/models/two_tower.py
"""
Two-Tower retrieval model definition and training script.

Run training:
  cd apps/uber/ml-platform
  python -m models.two_tower

After training, export to ONNX (see STEPS_8 for Triton config):
  The export happens at the bottom of this file (see __main__ block).
"""
import torch
import torch.nn as nn
import torch.nn.functional as F
import numpy as np
import mlflow
import mlflow.pytorch


class Tower(nn.Module):
    """Generic MLP tower for both user and ad encoders."""

    def __init__(self, input_dim: int, hidden_dims: list, embed_dim: int = 128):
        super().__init__()
        layers = []
        prev = input_dim
        for h in hidden_dims:
            layers += [nn.Linear(prev, h), nn.ReLU(), nn.BatchNorm1d(h)]
            prev = h
        layers += [nn.Linear(prev, embed_dim)]
        self.net = nn.Sequential(*layers)

    def forward(self, x):
        out = self.net(x)
        return F.normalize(out, dim=-1)   # L2 normalize → unit sphere


class TwoTowerModel(nn.Module):
    def __init__(self):
        super().__init__()
        self.user_tower = Tower(input_dim=20, hidden_dims=[64, 128])
        self.ad_tower   = Tower(input_dim=16, hidden_dims=[64, 128])

    def forward(self, user_features, ad_features):
        user_emb = self.user_tower(user_features)   # [B, 128]
        ad_emb   = self.ad_tower(ad_features)       # [B, 128]
        return user_emb, ad_emb


def infonce_loss(user_emb, ad_emb, temperature=0.07):
    """
    InfoNCE loss with in-batch negatives.

    For a batch of B (user, ad) positive pairs:
      - Similarity matrix S[i,j] = dot(user_i, ad_j)  (unit vectors → cosine sim)
      - Loss: -log(exp(S[i,i]/τ) / Σ_j exp(S[i,j]/τ))
      - Diagonal is positive; off-diagonal are in-batch negatives.

    Why in-batch negatives?
    Sampling true negatives is expensive (need label info).
    Other ads in the batch are "random" negatives — statistically good enough
    with large batch sizes (256+). GPU memory allows B=512 easily.
    """
    logits = torch.matmul(user_emb, ad_emb.T) / temperature  # [B, B]
    labels = torch.arange(len(user_emb), device=user_emb.device)
    return F.cross_entropy(logits, labels)


def train(epochs=10, batch_size=256, lr=1e-3):
    model = TwoTowerModel()
    optimizer = torch.optim.Adam(model.parameters(), lr=lr)

    mlflow.set_tracking_uri("http://localhost:5001")
    mlflow.set_experiment("two_tower_training")

    with mlflow.start_run():
        mlflow.log_params({"epochs": epochs, "batch_size": batch_size, "lr": lr})

        for epoch in range(epochs):
            # In production: load real impression/click data from Kafka → ClickHouse
            # Here: synthetic data for structure demonstration
            user_features = torch.randn(batch_size, 20)
            ad_features   = torch.randn(batch_size, 16)

            optimizer.zero_grad()
            user_emb, ad_emb = model(user_features, ad_features)
            loss = infonce_loss(user_emb, ad_emb)
            loss.backward()
            optimizer.step()

            mlflow.log_metric("loss", loss.item(), step=epoch)
            print(f"Epoch {epoch+1}/{epochs}  loss={loss.item():.4f}")

        mlflow.pytorch.log_model(model, "two_tower")

    return model


if __name__ == "__main__":
    model = train()

    # Export user tower to ONNX for Triton
    dummy_user = torch.randn(1, 20)
    torch.onnx.export(
        model.user_tower, dummy_user,
        "model_repository/user_tower/1/model.onnx",
        input_names=["input"], output_names=["output"],
        dynamic_axes={"input": {0: "batch"}, "output": {0: "batch"}},
        opset_version=17,
    )
    print("Exported user_tower ONNX")

    # Export ad tower to ONNX
    dummy_ad = torch.randn(1, 16)
    torch.onnx.export(
        model.ad_tower, dummy_ad,
        "model_repository/ad_tower/1/model.onnx",
        input_names=["input"], output_names=["output"],
        dynamic_axes={"input": {0: "batch"}, "output": {0: "batch"}},
        opset_version=17,
    )
    print("Exported ad_tower ONNX")
```

---

## File 7: models/dcn_ranker.py

```python
# apps/uber/ml-platform/models/dcn_ranker.py
"""
DCN v2 (Deep & Cross Network v2) ranker.

Architecture overview:
  Input: [N, 32] feature matrix
    Dense features (13): bid, ctr, cuisine_match, time cyclicals, etc.
    Sparse embeddings (19): user_id(8) + cuisine(8) + hour(3)
  Cross Network (3 layers): explicit bounded-degree feature interactions
  Deep Network: 256 → 128 → 64
  Output: Sigmoid → P(click)

Training data: (impression, label) pairs from Kafka ad-impression-events + ad-click-events
  label=1 if the impression resulted in a click within 5 minutes
  label=0 otherwise

Run training:
  python -m models.dcn_ranker
"""
import torch
import torch.nn as nn
import torch.nn.functional as F
import mlflow
import mlflow.pytorch


class CrossLayer(nn.Module):
    """
    Single Cross Network layer.

    Formula: x_{l+1} = x_0 ⊙ (W_l · x_l + b_l) + x_l

    Interpretation:
      x_0: original input (preserved across all layers)
      W_l · x_l: learned linear combination of current features
      Hadamard product with x_0: creates feature interactions
      + x_l: residual connection (preserves information)

    Each layer learns a different "interaction direction".
    3 layers → learns up to degree-4 feature interactions (2^2 × 3 = 12 paths).
    """
    def __init__(self, dim: int):
        super().__init__()
        self.W = nn.Linear(dim, dim, bias=True)

    def forward(self, x0, xl):
        return x0 * self.W(xl) + xl


class DCNRanker(nn.Module):
    def __init__(self, input_dim=32, num_cross_layers=3, deep_dims=(256, 128, 64)):
        super().__init__()

        # Cross network
        self.cross_layers = nn.ModuleList(
            [CrossLayer(input_dim) for _ in range(num_cross_layers)])

        # Deep network
        deep_layers = []
        prev = input_dim
        for d in deep_dims:
            deep_layers += [nn.Linear(prev, d), nn.ReLU(), nn.Dropout(0.1)]
            prev = d
        self.deep = nn.Sequential(*deep_layers)

        # Final prediction head
        self.head = nn.Linear(input_dim + deep_dims[-1], 1)

    def forward(self, x):
        # Cross path
        x0 = x
        xl = x
        for layer in self.cross_layers:
            xl = layer(x0, xl)
        cross_out = xl   # [N, input_dim]

        # Deep path
        deep_out = self.deep(x)   # [N, 64]

        # Concatenate and predict
        combined = torch.cat([cross_out, deep_out], dim=-1)  # [N, input_dim + 64]
        return torch.sigmoid(self.head(combined)).squeeze(-1)  # [N]


def train(epochs=20, batch_size=512, lr=1e-3):
    model = DCNRanker()
    optimizer = torch.optim.Adam(model.parameters(), lr=lr, weight_decay=1e-5)
    criterion = nn.BCELoss()

    # Class imbalance: ~2% click rate. Weight positives higher.
    # pos_weight=49 → model penalizes missing a click 49x more than a false positive.
    # Alternative: oversample positives or use focal loss.
    pos_weight = torch.tensor([49.0])
    criterion = nn.BCEWithLogitsLoss(pos_weight=pos_weight)

    mlflow.set_tracking_uri("http://localhost:5001")
    mlflow.set_experiment("dcn_ranker_training")

    with mlflow.start_run():
        mlflow.log_params({
            "epochs": epochs, "batch_size": batch_size,
            "num_cross_layers": 3, "deep_dims": "256-128-64",
            "pos_weight": 49,
        })

        for epoch in range(epochs):
            # Synthetic data — replace with real Kafka data pipeline
            features = torch.randn(batch_size, 32)
            labels   = (torch.rand(batch_size) < 0.02).float()   # ~2% click rate

            optimizer.zero_grad()
            preds = model(features)
            loss = criterion(preds, labels)
            loss.backward()
            optimizer.step()

            # AUC approximation (not true AUC, just for monitoring)
            mlflow.log_metric("bce_loss", loss.item(), step=epoch)
            print(f"Epoch {epoch+1}/{epochs}  loss={loss.item():.4f}")

        mlflow.pytorch.log_model(model, "dcn_ranker")

    return model


if __name__ == "__main__":
    model = train()

    # Export to ONNX for Triton
    dummy_input = torch.randn(1, 32)
    torch.onnx.export(
        model, dummy_input,
        "model_repository/dcn_ranker/1/model.onnx",
        input_names=["input"], output_names=["output"],
        dynamic_axes={"input": {0: "batch"}, "output": {0: "batch"}},
        opset_version=17,
    )
    print("Exported dcn_ranker ONNX")
```

---

## Running the ML Platform

```bash
cd apps/uber/ml-platform

# Install dependencies
pip install -r requirements.txt

# Train models and export ONNX (first time only)
python -m models.two_tower    # → model_repository/user_tower/1/model.onnx
python -m models.dcn_ranker   # → model_repository/dcn_ranker/1/model.onnx

# Start FastAPI service
uvicorn main:app --host 0.0.0.0 --port 8090 --reload

# Test retrieval stage
curl -X POST http://localhost:8090/api/ml/retrieve \
  -H "Content-Type: application/json" \
  -d '{"user_id":"user-001","latitude":37.77,"longitude":-122.41,"cuisine":"Italian"}'

# Test ranking stage (use ad IDs from retrieval response)
curl -X POST http://localhost:8090/api/ml/rank \
  -H "Content-Type: application/json" \
  -d '{"ad_ids":[1,2,3,4,5],"user_id":"user-001","cuisine":"Italian"}'

# View MLflow experiments
open http://localhost:5001
```

Continue to `STEPS_8.md` for Phase 8 — Triton Model Configs.
