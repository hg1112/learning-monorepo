# System Design — ByteDance MLE Inference Interview

## Design 1: LLM Inference Service (100K RPS)

### Requirements
- 100K requests/second, P99 latency < 500ms
- Model: 70B LLM (LLaMA/Qwen style)
- Variable prompt lengths (100–8K tokens), output up to 2K tokens

### Architecture

```
Client → Load Balancer → Gateway (auth, rate-limit, routing)
                              ↓
                    Request Queue (Kafka)
                              ↓
              ┌───────────────────────────────┐
              │     Inference Cluster         │
              │  ┌──────────┐  ┌──────────┐  │
              │  │ vLLM     │  │ vLLM     │  │
              │  │ Instance │  │ Instance │  │
              │  │ (8×A100) │  │ (8×A100) │  │
              │  └──────────┘  └──────────┘  │
              └───────────────────────────────┘
                              ↓
                    Response Queue → Client
```

### Key Design Decisions

| Decision | Choice | Why |
|----------|--------|-----|
| Batching | Continuous batching (vLLM) | 23x higher throughput vs static |
| KV Cache | PagedAttention | Near-zero fragmentation |
| Parallelism | Tensor Parallel (8 GPUs/node) | Within-node NVLink bandwidth |
| Quantization | INT8 weights + FP16 KV cache | 2x memory, maintain quality |
| Routing | Prefix-aware routing | Requests with same prompt prefix → same instance (KV cache hit) |

### Capacity Math
- 70B model in INT8: ~70 GB weights
- 8×A100 (80GB each) = 640 GB total → fits weights + 400 GB for KV cache
- KV cache per token (70B, 80 layers, 64 heads, 128 d_head, FP16):
  `2 * 80 * 64 * 128 * 2 = 2.6 MB/token`
- KV cache budget 400 GB → 153K tokens in flight
- At avg 1K tokens/request → ~150 concurrent requests per node
- 100K RPS at 1 token/step, 50 tokens avg output → 100K tokens/sec needed
- Each A100 does ~2000 tokens/sec (with TP=8, continuous batching)
- Need: 100K / 2000 = 50 nodes × 8 GPUs = 400 A100s

---

## Design 2: TikTok Recommendation System

### Requirements
- 1B+ users, 100M+ videos
- End-to-end latency < 200ms
- Real-time user behavior (last click < 1 second fresh)

### Pipeline

```
User Request
     ↓
[Feature Fetch] ← Redis (batch features), Kafka (real-time features)
     ↓
[Retrieval / Recall]
  ├── Two-tower ANN (Milvus, 1B items, top-500)        ~50ms
  ├── Collaborative filtering candidates (top-200)      ~20ms
  └── Author follow / trending candidates (top-100)     ~5ms
     ↓ (merge ~800 candidates, dedup)
[Pre-Ranking] — lightweight model, score all 800        ~20ms
     ↓ (top 200)
[Ranking] — DLRM/DIN, full feature cross, top-50       ~50ms
     ↓
[Post-Processing] — diversity, dedup, business rules    ~10ms
     ↓
Final Feed (50 items)
```

### Feature Engineering

```
Dense features (normalized floats):
  - User: age, gender embedding, region, device type
  - Video: duration, category, creator follower count

Sparse features (categorical → embedding lookup):
  - User ID (1B rows, 64-dim) — sharded across 32 param servers
  - Video ID (100M rows, 64-dim)
  - User interest tags (multi-hot, EmbeddingBag pooling)
  - Creator ID, Category ID

Sequence features:
  - Last 50 watched videos → Transformer encoder → 128-dim
  - Last 20 search queries → GRU → 64-dim
```

### Bottlenecks & Solutions

| Bottleneck | Solution |
|------------|----------|
| Embedding lookup latency | Cache hot embeddings in GPU HBM (top 1% users = 80% traffic) |
| ANN recall accuracy | Two-stage: coarse ANN (IVF) + rerank (HNSW on shortlist) |
| Feature staleness | Flink streaming pipeline, <30s lag for real-time features |
| Cold start (new video) | Content-based embedding (visual + audio features) from day 0 |

---

## Design 3: ML Training Infrastructure (for context)

```
Data Pipeline: HDFS → Spark preprocessing → TFRecord/WebDataset
Training: PyTorch FSDP + Megatron-LM (3D parallelism)
Experiment tracking: MLflow / internal platform
Model registry: versioned artifacts + A/B serving config
Deployment: Gradual rollout (1% → 10% → 100%) with metric monitoring
```

---

## Key Numbers to Memorize

| Metric | Value |
|--------|-------|
| A100 FP16 peak | 312 TFLOPS |
| A100 HBM bandwidth | 2 TB/s |
| A100 NVLink bandwidth | 600 GB/s |
| InfiniBand 400G | 50 GB/s |
| PCIe 4.0 | 32 GB/s |
| LLaMA-70B INT8 size | ~70 GB |
| LLaMA-7B INT8 size | ~7 GB |
| KV cache (70B, 1 token, FP16) | ~2.6 MB |
| vLLM throughput gain vs static | ~23x |
| Flash Attention speedup | 2-4x |
| Speculative decoding speedup | 2-3x |
| INT8 vs FP32 GEMM speedup | 2-4x |

---

## Flash Card: Questions You'll Definitely Be Asked

**Q: How does Flash Attention work?**
> Tiles Q, K, V into SRAM blocks. Computes exact attention without writing the full n×n matrix to HBM. O(n) memory, same output as standard attention. 2-4x faster due to reduced HBM bandwidth.

**Q: What is the arithmetic intensity of a matmul?**
> FLOPs = 2·M·N·K, Bytes = (M·K + K·N + M·N) · bytes_per_element. For large square matrices (n=4096, FP16): intensity ≈ 2n³ / (3n²·2) = n/3 ≈ 1365 FLOPs/byte → compute-bound on A100.

**Q: How does quantization affect accuracy?**
> FP16 → INT8: usually <0.5% degradation. INT8 → INT4: needs calibration (GPTQ/AWQ), 1-3% degradation. Activation quantization harder than weight quantization (outlier problem).

**Q: What is the decode phase bottleneck?**
> Memory bandwidth. Each decode step reads ALL model weights + KV cache but does only a tiny matmul (batch_size=1 or small). Arithmetic intensity ≈ 1-10 FLOPs/byte → 100-200x below compute peak. Solutions: large batches, speculative decoding, quantization.

**Q: Tensor parallel vs pipeline parallel for serving?**
> Tensor parallel: lower latency (all GPUs active every layer), requires NVLink. Pipeline parallel: higher throughput potential, but adds per-layer latency. For serving: prefer TP within a node (≤8 GPUs), PP across nodes only for very large models.
