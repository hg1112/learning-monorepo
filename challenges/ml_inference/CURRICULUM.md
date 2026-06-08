# ML Inference Curriculum — All Tracks

Status legend: `[ ]` not started · `[~]` in progress · `[x]` done

---

## Track B — GPU Programming (foundation first)

- [ ] B1 · GPU Architecture — thread hierarchy, memory hierarchy, warp scheduling
- [x] B2 · CUDA Basics (Numba) — vector add, matmul, shared memory
- [ ] B3 · Triton 101 — vector add → fused softmax → tiled matmul
- [ ] B4 · Memory Coalescing, bank conflicts, occupancy tuning
- [ ] B5 · Triton FlashAttention forward pass
- [ ] B6 · Triton FlashAttention backward pass

## Track A — LLM Inference Core

- [ ] A1 · Transformer Attention from scratch (QKV, softmax, multi-head)
- [ ] A2 · KV Cache — memory math, paged attention, flash_attn_with_kvcache
- [ ] A3 · FlashAttention — tiling algorithm, IO-complexity proof, FA2 vs FA3
- [ ] A4 · Quantization — INT8/FP8, GPTQ, AWQ, QLoRA
- [ ] A5 · Speculative Decoding — draft/verify loop, acceptance rate math
- [ ] A6 · Continuous Batching & PagedAttention (vLLM internals)
- [ ] A7 · Distributed Inference — tensor/pipeline/sequence parallelism
- [ ] A8 · MoE Architectures — sparse routing, DeepSeek/LLaMA4 style

## Track C — Ads ML Inference

- [ ] C1 · Two-Tower Models — embedding retrieval, ANN/HNSW
- [ ] C2 · DCN v2 — cross network for feature interactions
- [ ] C3 · Logistic Regression at scale — FTRL, feature hashing
- [ ] C4 · XGBoost / LightGBM / GBDT — gradient boosting first principles
- [ ] C5 · Isotonic Regression — calibration for CTR/CVR
- [ ] C6 · Bidding Systems — eCPM, auto-bidding, bid-aware retrieval
- [ ] C7 · LLM for Ads — generative ranking, retrieval-augmented ads

## Track D — System Design

- [ ] D1 · RecSys System Design (TikTok/ByteDance-style full stack)
- [ ] D2 · LLM Serving System Design (KV cache mgmt, batching, autoscaling)
- [ ] D3 · Online Learning + Feature Stores
- [ ] D4 · Inference Runtime Comparison (vLLM vs SGLang vs TRT-LLM vs TGI)

---

## Recommended Order (dependency-aware)

```
B1 → B2 → A1 → A2 → B3 → B4 → A3 → B5 → B6
                                      ↓
                          A4 → A5 → A6 → A7 → A8
                                      ↓
                          C1 → C2 → C3 → C4 → C5 → C6 → C7
                                      ↓
                          D1 → D2 → D3 → D4
```
