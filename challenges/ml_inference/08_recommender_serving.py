"""
TOPIC: Recommender System Inference at ByteDance Scale
ByteDance Interview Prep — MLE Inference

ByteDance's core product (TikTok) is a recommendation system.
Key interview questions:
  - How does a two-tower retrieval model work?
  - How do you serve embedding tables at 100B+ parameters?
  - What is the difference between retrieval and ranking?
  - How do you handle real-time features?
"""

import numpy as np
from dataclasses import dataclass


# ─────────────────────────────────────────────
# Two-Tower Model
# ─────────────────────────────────────────────

class Tower(object):
    """A simple MLP tower (user or item side)."""
    def __init__(self, input_dim, hidden_dim, output_dim):
        np.random.seed(0)
        self.W1 = np.random.randn(input_dim, hidden_dim) * 0.01
        self.W2 = np.random.randn(hidden_dim, output_dim) * 0.01

    def forward(self, x):
        h = np.maximum(0, x @ self.W1)
        out = h @ self.W2
        # L2 normalize for cosine similarity retrieval
        norm = np.linalg.norm(out, axis=-1, keepdims=True) + 1e-8
        return out / norm


class TwoTowerModel:
    """
    Two-Tower retrieval model (DPR / DSSM style).
    User and item towers produce embeddings; retrieval = nearest neighbor search.

    At ByteDance scale:
      - User tower: runs per request (fresh user features)
      - Item tower: precomputed for all items, stored in vector DB (Faiss / Milvus)
      - ANN search retrieves top-K candidates (~100ms for 1B items)
    """
    def __init__(self, user_feat_dim, item_feat_dim, embed_dim=128):
        self.user_tower = Tower(user_feat_dim, 256, embed_dim)
        self.item_tower = Tower(item_feat_dim, 256, embed_dim)

    def encode_user(self, user_features):
        return self.user_tower.forward(user_features)

    def encode_items(self, item_features):
        return self.item_tower.forward(item_features)

    def score(self, user_emb, item_embs):
        """Dot product similarity (after L2 norm = cosine similarity)."""
        return user_emb @ item_embs.T


# ─────────────────────────────────────────────
# Approximate Nearest Neighbor (HNSW sketch)
# ─────────────────────────────────────────────

class FlatIndexANN:
    """
    Flat (exact) nearest neighbor — baseline.
    Used for correctness validation; too slow for production (1B items).
    """
    def __init__(self):
        self.embeddings = None
        self.item_ids = None

    def build(self, item_embeddings, item_ids):
        self.embeddings = item_embeddings   # (N, D)
        self.item_ids = item_ids

    def search(self, query_emb, k=10):
        """Brute force: O(N*D)"""
        scores = query_emb @ self.embeddings.T
        top_k = np.argsort(scores)[::-1][:k]
        return [(self.item_ids[i], scores[i]) for i in top_k]


"""
Production ANN options at ByteDance scale:

1. Faiss (Facebook):
   - IVF (Inverted File): cluster items into C centroids,
     search only nearby clusters. O(N/C * D) per query.
   - HNSW: graph-based, very fast query, large memory footprint.
   - IVF-PQ: product quantization compresses embeddings 8-32x.

2. Milvus (open-source, used at ByteDance):
   - Distributed vector DB built on Faiss + Kubernetes.
   - Supports multi-tenancy, real-time insertion.

HNSW intuition:
  - Build hierarchical graph: top layers = highway (few nodes, long edges)
  - Bottom layer = full graph (all nodes, short edges)
  - Search: start at top, greedily navigate to query, descend layers
  - O(log N) query time, ~98% recall@10 vs exact search
"""


# ─────────────────────────────────────────────
# Ranking Model (DLRM-style)
# ─────────────────────────────────────────────

class EmbeddingTable:
    """
    Sparse embedding lookup — the dominant cost in recommendation models.
    At ByteDance: tables can be 10B+ rows, 100GB+ in memory.
    Sharded across many parameter servers.
    """
    def __init__(self, num_embeddings, embedding_dim):
        self.table = np.random.randn(num_embeddings, embedding_dim).astype(np.float32) * 0.01

    def lookup(self, indices):
        """
        Sparse lookup: fetch rows for a batch of categorical feature IDs.
        Real implementation: EmbeddingBag (sum/mean pooling over variable-length bags).
        """
        return self.table[indices]


class DLRM:
    """
    Deep Learning Recommendation Model (Facebook, 2019) — similar to ByteDance's architecture.

    Architecture:
      Dense features → bottom MLP → dense embedding
      Sparse features → embedding tables → sparse embeddings
      Interaction: dot products of all pairs (dense + sparse embeddings)
      Top MLP → CTR probability
    """
    def __init__(self, dense_dim, sparse_configs, embed_dim=32, top_mlp_dims=[64, 32]):
        # Bottom MLP: dense features → embed_dim
        self.bottom_W1 = np.random.randn(dense_dim, 64) * 0.01
        self.bottom_W2 = np.random.randn(64, embed_dim) * 0.01

        # Sparse embedding tables
        self.emb_tables = [
            EmbeddingTable(num_emb, embed_dim)
            for num_emb, _ in sparse_configs
        ]

        # Interaction features dim: (num_sparse + 1) choose 2 pairwise dots
        num_features = len(sparse_configs) + 1  # +1 for dense embedding
        interact_dim = num_features * (num_features - 1) // 2

        # Top MLP: interaction features → CTR
        in_dim = interact_dim + embed_dim
        layers = []
        prev = in_dim
        for d in top_mlp_dims:
            layers.append((np.random.randn(prev, d) * 0.01,))
            prev = d
        self.top_layers = [np.random.randn(prev, d) * 0.01 for d in top_mlp_dims]
        self.top_out = np.random.randn(top_mlp_dims[-1], 1) * 0.01

    def bottom_mlp(self, dense_features):
        h = np.maximum(0, dense_features @ self.bottom_W1)
        return np.maximum(0, h @ self.bottom_W2)

    def interact_features(self, embeddings):
        """
        Compute pairwise dot products between all embeddings.
        This is the key DLRM interaction layer.
        """
        n = len(embeddings)
        dots = []
        for i in range(n):
            for j in range(i + 1, n):
                dot = (embeddings[i] * embeddings[j]).sum(axis=-1, keepdims=True)
                dots.append(dot)
        return np.concatenate(dots, axis=-1)

    def forward(self, dense_features, sparse_indices_list):
        dense_emb = self.bottom_mlp(dense_features)                     # (batch, embed_dim)
        sparse_embs = [t.lookup(idx) for t, idx in zip(self.emb_tables, sparse_indices_list)]

        all_embs = [dense_emb] + sparse_embs
        interaction = self.interact_features(all_embs)                  # (batch, n*(n-1)/2)
        x = np.concatenate([dense_emb, interaction], axis=-1)

        for W in self.top_layers:
            x = np.maximum(0, x @ W)
        logit = x @ self.top_out
        return 1 / (1 + np.exp(-logit))   # sigmoid → CTR probability


# ─────────────────────────────────────────────
# Real-time feature serving
# ─────────────────────────────────────────────
"""
Feature Pipeline at ByteDance Scale:

1. Batch features (precomputed, low staleness ok):
   - User long-term interest embeddings (computed daily)
   - Item statistics (view count, like rate over 7/30 days)
   - Stored in: key-value store (Redis / ByteKV / Abase)

2. Near-real-time features (minutes latency):
   - User's recent interaction sequence (last 100 items viewed)
   - Streaming pipeline: Kafka → Flink → feature store

3. Real-time features (milliseconds, computed inline):
   - Current session context (device, time of day, scroll position)
   - Computed at request time in serving logic

4. Sequence modeling:
   - User's historical clicks → Transformer encoder → user embedding
   - SIM (Search-based Interest Model): efficient sparse attention over history
   - BST (Behavior Sequence Transformer): Alibaba / ByteDance style
"""


# ─────────────────────────────────────────────
# Demo
# ─────────────────────────────────────────────
if __name__ == "__main__":
    np.random.seed(42)

    print("=== Two-Tower Retrieval ===")
    model = TwoTowerModel(user_feat_dim=64, item_feat_dim=32, embed_dim=128)

    user_feat = np.random.randn(1, 64)
    item_feats = np.random.randn(10000, 32)

    user_emb = model.encode_user(user_feat)            # (1, 128)
    item_embs = model.encode_items(item_feats)         # (10000, 128)
    scores = model.score(user_emb, item_embs)[0]       # (10000,)

    top_k = np.argsort(scores)[::-1][:5]
    print(f"User embedding shape: {user_emb.shape}")
    print(f"Top-5 item indices: {top_k}")
    print(f"Top-5 scores: {scores[top_k].round(4)}")

    print("\n=== ANN Index ===")
    index = FlatIndexANN()
    index.build(item_embs, list(range(10000)))
    results = index.search(user_emb[0], k=5)
    print(f"ANN top-5: {[(iid, round(float(s), 4)) for iid, s in results]}")

    print("\n=== DLRM Ranking ===")
    dlrm = DLRM(
        dense_dim=13,
        sparse_configs=[(10000, 32), (50000, 32), (100000, 32)],
        embed_dim=32,
    )
    batch = 8
    dense = np.random.randn(batch, 13).astype(np.float32)
    sparse = [np.random.randint(0, n, size=batch) for n, _ in [(10000,0),(50000,0),(100000,0)]]
    ctr = dlrm.forward(dense, sparse)
    print(f"CTR predictions: {ctr.flatten().round(4)}")

"""
INTERVIEW TALKING POINTS:

Q: What is the difference between retrieval and ranking?
A: Retrieval (Recall): fast, approximate — narrows 1B items to ~1000 candidates.
   Uses simple models (two-tower) and ANN search. Latency budget: ~100ms.
   Ranking: accurate, slower — scores the 1000 candidates precisely.
   Uses complex models (DLRM, cross-feature interactions). Budget: ~50ms.

Q: How do you scale embedding tables beyond single-GPU memory?
A: Shard tables across parameter servers (CPU memory or multiple GPUs).
   Each server owns a subset of rows; lookups are distributed.
   ByteDance uses custom KV stores (Abase, similar to Redis) for this.

Q: What is the biggest bottleneck in recommendation inference?
A: Memory bandwidth for embedding lookups. Sparse features = random access
   into huge tables → poor cache utilization.
   Solutions: caching hot embeddings in GPU HBM, mixed precision (FP16),
   embedding compression (hashing tricks, QR decomposition).

Q: How does ByteDance handle 100ms end-to-end latency for TikTok?
A: Pipeline: retrieval (ANN) → pre-ranking (small model) → ranking (DLRM)
   → post-processing (diversity, business rules) all in <100ms.
   Heavy use of caching, feature precomputation, model distillation.
"""
