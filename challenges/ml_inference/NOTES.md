# MLE Inference Interview Notes
ByteDance · April 21, 2026

---

## YouTube Study References

### LLM Inference & KV Cache
- [LLM inference optimization: Architecture, KV cache and Flash Attention](https://www.youtube.com/watch?v=jk2FsJxZFo8) — best single overview video
- [KV Cache Crash Course](https://www.youtube.com/watch?v=SLYUBsZE72E) — focused deep dive
- [Efficient LLM Inference — vLLM, KV Cache, Flash Decoding](https://www.youtube.com/watch?v=yVXtLTcdO1Q) — University of Waterloo lecture
- [How to make LLMs fast: KV Caching, Speculative Decoding](https://www.youtube.com/watch?v=PncVSWbxdWU)
- [Optimize LLM inference with vLLM](https://www.youtube.com/watch?v=lxjWiVuK5cA)

### Speculative Decoding
- [Speculative Decoding: 3× Faster LLM Inference with Zero Quality Loss](https://www.youtube.com/watch?v=Qh9cIEelCj4)
- [Faster LLMs: Accelerate Inference with Speculative Decoding](https://www.youtube.com/watch?v=VkWlLSTdHs8)

### GPU Kernels & Triton ← PRIMARY FOCUS
- [Triton GPU Kernels 101 — full playlist (start here)](https://www.youtube.com/watch?v=TUQAyCNxFe4) ⭐
- [Flash Attention derived from first principles with Triton](https://www.youtube.com/watch?v=zy8ChVd_oTM) ⭐
- [Flash Attention fwd pass — Triton Kernels 101 Lesson 9](https://www.youtube.com/watch?v=6ap2QVWKFH0) ⭐
- [Flash Attention bwd pass — Triton Kernels 101 Lesson 10](https://www.youtube.com/watch?v=cygYBmB5ow8)
- [Fused Softmax — Triton Kernels 101 Lesson 5](https://www.youtube.com/watch?v=ftknUZDQCPc)
- [Practitioners Guide to Triton (Lecture 14)](https://www.youtube.com/watch?v=DdTsX6DQk24)
- [GPU Programming with Triton Kernels — DevConf.US 2025](https://www.youtube.com/watch?v=sv4soasZK7U)
- [Torch to Triton LLM Tutorial](https://www.youtube.com/watch?v=ZfjV_GTJLPI)
- [GPU Memory Coalescing Explained — Warp-Level Optimization](https://www.youtube.com/watch?v=zdzg0m279zA)
- [Memory Coalescing, Bank Conflicts, Data Staging](https://www.youtube.com/watch?v=4bYLFhMtAqw)
- [Warp Scheduling and Divergence (Lecture 16)](https://www.youtube.com/watch?v=WClew-fqVkM)
- [Getting Started with CUDA — NVIDIA GTC 2025](https://www.youtube.com/watch?v=GmNkYayuaA4)

### Quantization
- [LLM Quantization Explained: GPTQ, AWQ, QLoRA, GGUF and More](https://www.youtube.com/watch?v=WmvZwR4rKJg) — most comprehensive
- [LLM Fine-Tuning: Quantization Explained Part 1](https://www.youtube.com/watch?v=sLEuVm9ZdxQ) — PTQ, QAT, theory
- [LLM Fine-Tuning: Quantization Explained Part 2](https://www.youtube.com/watch?v=_3FctggJ9r4) — GPTQ, AWQ, GGUF, llama.cpp
- [Which Quantization Method is Right for You? GPTQ vs GGUF vs AWQ](https://www.youtube.com/watch?v=mNE_d-C82lI)

### Recommendation Systems
- [Design TikTok's Recommendation System — ML System Design](https://www.youtube.com/watch?v=Gscelu22FWI) — most relevant to ByteDance
- [Build TikTok's Personalized Real-Time Recommendation System](https://www.youtube.com/watch?v=skZ1HcF7AsM)
- [Building Scalable Retrieval with Two-Tower Models](https://www.youtube.com/watch?v=o-pZk5R0TZg)
- [Using DLRM — Building Recommender Systems with PyTorch](https://www.youtube.com/watch?v=r9J3UZmddC4)

---

## 1. GPU Memory Hierarchy

```mermaid
graph TD
    R["Registers<br/>255 per thread · &lt;1 cycle · thread-private"]
    S["Shared Memory / L1 SRAM<br/>164 KB per SM · ~4 cycles · block-shared"]
    L2["L2 Cache<br/>40–80 MB · ~200 cycles · device-wide"]
    HBM["HBM — Global Memory<br/>80 GB · 2 TB/s · ~600 cycles"]

    R -->|spill| S
    S -->|miss| L2
    L2 -->|miss| HBM

    style R fill:#2d6a4f,color:#fff
    style S fill:#1e6091,color:#fff
    style L2 fill:#6b4226,color:#fff
    style HBM fill:#7b2d8b,color:#fff
```

> **Rule:** every unnecessary HBM read/write kills performance. Kernel fusion and tiling keep data in SRAM.

---

## 2. Roofline Model — A100

```mermaid
xychart-beta
    title "A100 Roofline (FP16)"
    x-axis "Arithmetic Intensity (FLOPs/byte)" [1, 10, 50, 100, 156, 200, 500, 1000]
    y-axis "Achievable TFLOPS" 0 --> 350
    line [2, 20, 100, 200, 312, 312, 312, 312]
```

| Operation | Intensity | Bound |
|-----------|-----------|-------|
| MatMul n=4096 FP16 | ~1365 FLOPs/byte | **Compute** |
| Prefill seq=2048 | ~600 FLOPs/byte | **Compute** |
| Softmax | ~3 FLOPs/byte | **Memory** |
| LayerNorm | ~2 FLOPs/byte | **Memory** |
| Decode bs=1 | ~1 FLOPs/byte | **Memory** ← the bottleneck |

> Ridge point = 312 TFLOPS ÷ 2 TB/s = **156 FLOPs/byte**

---

## 3. Transformer Layer

```mermaid
flowchart TD
    IN["Input\nbatch × seq × d_model"]
    LN1["LayerNorm"]
    MHA["Multi-Head Attention\nh heads · d_k = d_model ÷ h\nO of n² · d time"]
    R1(["＋ Residual"])
    LN2["LayerNorm"]
    FFN["FFN\nLinear → GELU → Linear\nd_model → 4·d_model → d_model"]
    R2(["＋ Residual"])
    OUT["Output\nbatch × seq × d_model"]

    IN --> LN1 --> MHA --> R1 --> LN2 --> FFN --> R2 --> OUT
    IN --> R1
    R1 --> R2
```

---

## 4. Scaled Dot-Product Attention Flow

```mermaid
flowchart LR
    Q["Q\nbatch·heads·seq·d_k"]
    K["K\nbatch·heads·seq·d_k"]
    V["V\nbatch·heads·seq·d_v"]

    QK["Q @ Kᵀ ÷ √d_k\nScores"]
    MASK["+ Causal Mask\nupper-tri = −∞"]
    SM["Softmax\nover key dim"]
    OUT["@ V\nOutput"]

    Q --> QK
    K --> QK
    QK --> MASK --> SM --> OUT
    V --> OUT
```

---

## 5. Causal Mask Pattern

```mermaid
block-beta
    columns 5
    space A["pos A"] B["pos B"] C["pos C"] D["pos D"]
    A1["A"] AA["✓ 1"] AB["✗ 0"] AC["✗ 0"] AD["✗ 0"]
    B1["B"] BA["✓ 1"] BB["✓ 1"] BC["✗ 0"] BD["✗ 0"]
    C1["C"] CA["✓ 1"] CB["✓ 1"] CC["✓ 1"] CD["✗ 0"]
    D1["D"] DA["✓ 1"] DB["✓ 1"] DC["✓ 1"] DD["✓ 1"]
```

> ✗ positions → score set to −∞ → exp(−∞) = 0 after softmax. Future tokens are invisible.

---

## 6. KV Cache — Prefill vs Decode

```mermaid
sequenceDiagram
    participant C as Client
    participant G as GPU

    Note over C,G: PREFILL — compute-bound, parallel
    C->>G: Full prompt [T1 T2 T3 T4 T5]
    G->>G: Compute K,V for ALL tokens simultaneously
    G->>G: Store K[1..5], V[1..5] in cache
    G-->>C: First output token T6

    Note over C,G: DECODE — memory-bound, sequential
    C->>G: Token T6
    G->>G: Compute K,V for T6 only (1 token!)
    G->>G: Attend over full cache K[1..6], V[1..6]
    G-->>C: T7

    C->>G: Token T7
    G->>G: Cache grows: K[1..7], V[1..7]
    G-->>C: T8

    Note over G: Repeat until EOS or max_len
```

**Memory cost per token:**
`2 × num_layers × num_heads × d_head × bytes_per_element`

| Model | Per Token (FP16) | 4096 ctx total |
|-------|-----------------|----------------|
| LLaMA-7B | ~0.25 MB | ~1 GB |
| LLaMA-70B | ~2.6 MB | ~10.7 GB |

---

## 7. PagedAttention — Block Manager

```mermaid
flowchart TD
    subgraph Physical KV Blocks
        B0["Block 0\n16 tokens"]
        B1["Block 1\n16 tokens"]
        B2["Block 2\n16 tokens"]
        B3["Block 3\n16 tokens"]
        B4["Block 4\n16 tokens"]
        B5["Block 5\n16 tokens"]
    end

    subgraph Request A ["Request A (48 tokens)"]
        AT["Block Table: 0→B0, 1→B2, 2→B4"]
    end

    subgraph Request B ["Request B (32 tokens)"]
        BT["Block Table: 0→B1, 1→B3"]
    end

    subgraph Request C ["Request C (16 tokens, same prefix as A)"]
        CT["Block Table: 0→B0 (shared!), 1→B5"]
    end

    AT --> B0
    AT --> B2
    AT --> B4
    BT --> B1
    BT --> B3
    CT -->|Copy-on-Write| B0
    CT --> B5
```

> Blocks allocated on demand, freed immediately on completion. Near-zero fragmentation.

---

## 8. Flash Attention vs Standard Attention

```mermaid
flowchart LR
    subgraph Standard ["Standard Attention — O(n²) memory"]
        direction TB
        sQ["Q"] --> sS["Q@Kᵀ\nn×n matrix\nwritten to HBM"]
        sK["K"] --> sS
        sS --> sSM["Softmax\nread/write HBM again"]
        sSM --> sO["@V → Output\nread HBM again"]
        sV["V"] --> sO
    end

    subgraph Flash ["Flash Attention — O(n) memory"]
        direction TB
        fQ["Q tile\n→ SRAM"] --> fC["Compute scores\nstay in SRAM\nonline softmax update"]
        fK["K tile\n→ SRAM"] --> fC
        fV["V tile\n→ SRAM"] --> fC
        fC --> fO["Accumulate output\nwrite once to HBM"]
    end
```

**Online softmax update** (key math — never stores n×n):
```
m_new = max(m_old, max(S_block))
l_new = exp(m_old − m_new) · l_old + Σ exp(S_block − m_new)
O_new = (exp(m_old − m_new) · O_old + exp(S_block − m_new) @ V_block) / l_new
```
Result is **mathematically identical** to standard attention — exact, not approximate.

---

## 9. Quantization Methods

```mermaid
flowchart TD
    Q["Quantization"]

    Q --> PTQ["Post-Training Quantization\nNo retraining needed"]
    Q --> QAT["Quantization-Aware Training\nSimulate noise during training\nBetter accuracy at low bits"]

    PTQ --> ABS["Absmax / Symmetric\nRange: −max to +max → INT8\nGood for weights"]
    PTQ --> ZP["Zero-Point / Asymmetric\nRange: min to max → UINT8\nGood for ReLU activations"]
    PTQ --> PC["Per-Channel\nOne scale per output neuron\nMuch better accuracy than per-tensor"]
    PTQ --> GPTQ["GPTQ\nINT4 weights, column-by-column\nHessian-based error compensation"]
    PTQ --> AWQ["AWQ\nScale important channels before quant\nNo Hessian needed, faster than GPTQ"]
```

| Method | Bits | Memory | Quality loss | Speed |
|--------|------|--------|-------------|-------|
| FP16 | 16 | 50% | ~0% | 2× |
| INT8 | 8 | 25% | <0.5% | 4× |
| INT4 (GPTQ/AWQ) | 4 | 12.5% | 1–3% | 8× |
| INT4 KV cache | 4 | KV only | <0.5% | — |

---

## 10. Speculative Decoding

```mermaid
sequenceDiagram
    participant D as Draft Model (small, fast)
    participant T as Target Model (large, slow)

    Note over D: Generate γ=4 tokens autoregressively
    D->>D: token₁ ~ p_draft(·|prompt)
    D->>D: token₂ ~ p_draft(·|..token₁)
    D->>D: token₃ ~ p_draft(·|..token₂)
    D->>D: token₄ ~ p_draft(·|..token₃)

    Note over T: ONE forward pass — scores all 4 positions in parallel
    D->>T: [prompt, tok₁, tok₂, tok₃, tok₄]
    T->>T: Compute p_target at each position

    Note over T: Accept/Reject loop
    T->>T: tok₁ accepted  (p_t/p_d ≥ random)
    T->>T: tok₂ accepted
    T->>T: tok₃ REJECTED → resample from max(0, p_t−p_d)
    T-->>D: Return [tok₁✓, tok₂✓, tok₃_new]
```

**Expected speedup:**
| Acceptance α | E[tokens/call] | Speedup (draft 10× cheaper) |
|-------------|---------------|----------------------------|
| 0.5 | 1.94 | ~1.4× |
| 0.7 | 2.77 | ~2.0× |
| 0.9 | 4.10 | ~2.9× |

> **Why lossless?** Combined marginal = min(p_t, p_d)/Z + max(0, p_t−p_d)/Z = p_target exactly.

---

## 11. Tensor Parallelism (Megatron-LM)

```mermaid
flowchart LR
    X["Input X\nd_model"]

    subgraph Col ["Column Parallel — no AllReduce"]
        W0["GPU 0\nW₁[:, 0:ffn/4]"]
        W1["GPU 1\nW₁[:, ffn/4:ffn/2]"]
        W2["GPU 2\nW₁[:, ffn/2:3ffn/4]"]
        W3["GPU 3\nW₁[:, 3ffn/4:]"]
    end

    subgraph GELU ["GELU (local)"]
        G0["GPU 0"] 
        G1["GPU 1"]
        G2["GPU 2"]
        G3["GPU 3"]
    end

    subgraph Row ["Row Parallel — AllReduce at end"]
        R0["GPU 0\nW₂[0:ffn/4, :]"]
        R1["GPU 1\nW₂[ffn/4:ffn/2, :]"]
        R2["GPU 2\nW₂[ffn/2:3ffn/4, :]"]
        R3["GPU 3\nW₂[3ffn/4:, :]"]
    end

    AR(["AllReduce\nsum across GPUs"])
    OUT["Output Z\nd_model"]

    X --> W0 & W1 & W2 & W3
    W0 --> G0 --> R0
    W1 --> G1 --> R1
    W2 --> G2 --> R2
    W3 --> G3 --> R3
    R0 & R1 & R2 & R3 --> AR --> OUT
```

> Only **1 AllReduce per FFN layer**. Communication happens over NVLink (600 GB/s intra-node).

---

## 12. Pipeline Parallelism & Bubble

```mermaid
gantt
    title Pipeline Parallelism — 4 GPUs, 4 Microbatches (F=Forward, B=Backward)
    dateFormat  X
    axisFormat %s

    section GPU 0
    mb0 F :0, 1
    mb1 F :1, 2
    mb2 F :2, 3
    mb3 F :3, 4
    Bubble :crit, 4, 7
    mb0 B :7, 8
    mb1 B :8, 9
    mb2 B :9, 10
    mb3 B :10, 11

    section GPU 1
    Bubble :crit, 0, 1
    mb0 F :1, 2
    mb1 F :2, 3
    mb2 F :3, 4
    mb3 F :4, 5
    Bubble :crit, 5, 7
    mb0 B :7, 8
    mb1 B :8, 9
    mb2 B :9, 10

    section GPU 2
    Bubble :crit, 0, 2
    mb0 F :2, 3
    mb1 F :3, 4
    mb2 F :4, 5
    mb3 F :5, 6
    Bubble :crit, 6, 7
    mb0 B :7, 8
    mb1 B :8, 9

    section GPU 3
    Bubble :crit, 0, 3
    mb0 F :3, 4
    mb1 F :4, 5
    mb2 F :5, 6
    mb3 F :6, 7
    mb0 B :7, 8
```

**Bubble ratio** = (p − 1) / (m + p − 1)

| Microbatches (m) | Bubble (p=4) |
|-----------------|-------------|
| 1 | 75% |
| 4 | 43% |
| 16 | 17% |
| 32 | 9% |

---

## 13. Continuous vs Static Batching

```mermaid
gantt
    title Static Batching — GPU idles waiting for longest request
    dateFormat X
    axisFormat %s

    section Batch 1
    Req A (20 tok) :0, 20
    Req B (padded to 20) :0, 20
    Req C (padded to 20) :0, 20
    Req D (padded to 20) :0, 20

    section Batch 2 (waits for batch 1)
    Req E :20, 40
    Req F :20, 40
    Req G :20, 40
    Req H :20, 40
```

```mermaid
gantt
    title Continuous Batching — slots filled immediately on completion
    dateFormat X
    axisFormat %s

    section Slot 0
    Req A (20 tok) :0, 20
    Req E (starts immediately) :20, 30

    section Slot 1
    Req B (4 tok) :0, 4
    Req F (starts immediately) :4, 16

    section Slot 2
    Req C (7 tok) :0, 7
    Req G (starts immediately) :7, 20

    section Slot 3
    Req D (9 tok) :0, 9
    Req H (starts immediately) :9, 25
```

> Continuous batching = ~23× throughput improvement over static (Orca paper, 2022).

---

## 14. Two-Tower Retrieval — TikTok

```mermaid
flowchart LR
    subgraph UserTower ["User Tower (online)"]
        UF["User Features\nage · history · device"] --> UMLP["MLP 256→128"] --> UE["User Embedding\n128-dim, L2 norm"]
    end

    subgraph ItemTower ["Item Tower (offline, precomputed)"]
        IF["Item Features\ncategory · duration · tags"] --> IMLP["MLP 256→128"] --> IE["Item Embeddings\n100M items precomputed"]
    end

    subgraph VectorDB ["Vector DB (Milvus / Faiss)"]
        IE --> IDX["ANN Index\nHNSW / IVF-PQ"]
    end

    UE -->|ANN Search ~50ms| IDX --> CANDS["Top-500 Candidates"]
```

---

## 15. Full TikTok Recommendation Pipeline

```mermaid
flowchart TD
    USER["User Opens App"]

    FF["Feature Fetch ~10ms\nRedis batch features\nKafka near-real-time\nRequest context inline"]

    subgraph Retrieval ["Retrieval — Recall ~70ms"]
        TT["Two-Tower ANN\ntop-500"]
        CF["Collab Filtering\ntop-200"]
        TR["Trending / Follow\ntop-100"]
    end

    MERGE["Merge + Dedup\n~800 candidates"]

    PRE["Pre-Ranking ~20ms\nLightweight model\n→ top-200"]

    RANK["Ranking ~50ms\nDLRM / DIN\npCTR · pLike · pShare · pFinish\n→ top-50"]

    POST["Post-Processing ~10ms\nDiversity · Dedup\nBusiness rules · Ads mix"]

    FEED["50 Videos Served\nTotal < 200ms"]

    USER --> FF --> TT & CF & TR --> MERGE --> PRE --> RANK --> POST --> FEED
```

---

## 16. DLRM Architecture

```mermaid
flowchart TD
    DF["Dense Features\n13 floats"] --> BMLP["Bottom MLP\n13 → 64 → 32-dim"]

    subgraph EmbTables ["Embedding Tables — sharded across param servers"]
        UID["User ID\n1B rows · 64-dim"]
        VID["Video ID\n100M rows · 64-dim"]
        TAGS["Interest Tags\n500K rows · 64-dim"]
    end

    BMLP --> INT
    UID & VID & TAGS --> INT

    INT["Interaction Layer\nPairwise dot products\nn×n-1÷2 features"]

    INT --> CONCAT["Concat with dense embedding"]

    CONCAT --> TMLP["Top MLP\n→ 256 → 128 → 64 → 1"]

    TMLP --> SIG["Sigmoid → pCTR"]
```

---

## 17. Triton Kernel Thread Model

```mermaid
flowchart TD
    GRID["Grid\nlaunches kernel\nN programs"]

    subgraph Program ["One Program (like a CUDA block)"]
        PID["program_id = which tile"]
        RANGE["tl.arange = thread indices within block"]
        LOAD["tl.load — vectorized HBM read with mask"]
        COMPUTE["Computation in registers / SRAM"]
        STORE["tl.store — vectorized HBM write"]
    end

    GRID --> Program

    subgraph Fused ["Fused Softmax — 1 kernel vs PyTorch's 3"]
        R1["Read row from HBM → SRAM"]
        R2["Compute max (numerical stability)"]
        R3["Compute exp, sum — all in SRAM"]
        R4["Normalize — SRAM"]
        R5["Write result → HBM"]
        R1 --> R2 --> R3 --> R4 --> R5
    end
```

> **No manual shared memory management.** Triton auto-tiles and schedules warps. Write tiled kernels in Python with near-CUDA performance.

---

## 18. Tiled GEMM vs Naïve GEMM

```mermaid
flowchart LR
    subgraph Naive ["Naïve GEMM — O(n³) HBM reads"]
        NA["For each C[i,j]:\nLoad row i of A from HBM\nLoad col j of B from HBM\nCompute dot product"]
    end

    subgraph Tiled ["Tiled GEMM — O(n³ ÷ TILE) HBM reads"]
        TA["Load A-tile into SRAM\n(32×32 block)"]
        TB["Load B-tile into SRAM\n(32×32 block)"]
        TC["Compute all dot products\nwithin tile — stay in SRAM"]
        TD["Move to next tile\naccumulate into C"]
        TA --> TC
        TB --> TC
        TC --> TD --> TA
    end
```

> TILE=32 → 32× fewer HBM reads. Arithmetic intensity scales with TILE → compute-bound for large tiles.

---

## 19. Sampling Strategies

```mermaid
flowchart TD
    LOGITS["Logits from model\n[2.1, 0.5, 1.8, 0.2, 0.4]\n→ probs [0.47, 0.06, 0.35, 0.04, 0.07]"]

    LOGITS --> GREEDY["Greedy\nargmax → always token A\nDeterministic, repetitive"]

    LOGITS --> BEAM["Beam Search k=3\nKeep top-3 sequences\nexpand each step\nHigher prob than greedy"]

    LOGITS --> TOPK["Top-K k=3\nSample from {A, C, E}\nFixed vocab size"]

    LOGITS --> TOPP["Top-P nucleus p=0.9\nSort by prob, take until cumsum ≥ 0.9\n→ {A, C, E, B}\nAdaptive vocab — better than top-k"]

    LOGITS --> TEMP["Temperature\nT<1 → sharper → deterministic\nT>1 → flatter → creative"]
```

---

## 20. Which Optimization to Apply

```mermaid
flowchart TD
    START["Inference too slow / expensive"]
    START --> Q1{"What's the bottleneck?"}

    Q1 -->|Memory-bound\nsmall batch, decode phase| MEM
    Q1 -->|Compute-bound\nlarge batch, prefill phase| COMP
    Q1 -->|Model too large\nfor single GPU| DIST

    subgraph MEM ["Memory Optimizations"]
        M1["Larger batch size"]
        M2["Continuous batching → ~23×"]
        M3["INT8 / INT4 KV cache → 2–4× more tokens"]
        M4["Speculative decoding → 2–3× decode"]
    end

    subgraph COMP ["Compute Optimizations"]
        C1["INT8 weights → 2–4× GEMM"]
        C2["Flash Attention → 2–4× attention"]
        C3["Kernel fusion → fewer HBM trips"]
        C4["Custom Triton kernels"]
    end

    subgraph DIST ["Distributed Inference"]
        D1{"How many GPUs?"}
        D1 -->|2–8 same node NVLink| D2["Tensor Parallelism"]
        D1 -->|Multi-node| D3["Tensor + Pipeline\n3D Parallelism"]
        D1 -->|Multiple replicas| D4["Data Parallelism"]
    end
```

---

## 21. Key Numbers Cheat Sheet

| Category | Metric | Value |
|----------|--------|-------|
| **A100** | FP16 Tensor Core peak | 312 TFLOPS |
| **A100** | HBM bandwidth | 2 TB/s |
| **A100** | HBM capacity | 80 GB |
| **A100** | NVLink (intra-node) | 600 GB/s |
| **Network** | InfiniBand 400G | 50 GB/s |
| **Network** | PCIe 4.0 | 32 GB/s |
| **Models** | LLaMA-7B INT8 | ~7 GB |
| **Models** | LLaMA-70B INT8 | ~70 GB |
| **KV Cache** | 70B model, 1 token FP16 | ~2.6 MB |
| **KV Cache** | 7B model, 1 token FP16 | ~0.25 MB |
| **Speedups** | Continuous batching vs static | ~23× |
| **Speedups** | Flash Attention | 2–4× |
| **Speedups** | Speculative decoding (α=0.8) | ~2.5× |
| **Speedups** | INT8 GEMM vs FP32 | 2–4× |

---

## 22. Interview Answer Templates

**Q: Walk me through a full LLM inference request.**

```mermaid
flowchart TD
    A["Request arrives\nGateway: auth, rate-limit token bucket"] -->
    B["Scheduler admits request\nPagedAttention allocates KV blocks"] -->
    C["PREFILL: prompt processed in parallel\nK,V stored in cache"] -->
    D["DECODE loop\nContinuous batch scheduler adds/removes each step"] -->
    E["Optional: draft model generates γ tokens\nTarget model verifies in one forward pass"] -->
    F["Sample next token\nnucleus / greedy"] -->
    G["Append K,V to cache\nStream token to client"] -->
    H{"EOS or max_len?"}
    H -->|No| D
    H -->|Yes| I["Free KV cache blocks\nRemove from batch"]
```

**Q: How do you cut serving cost 2×?**

```mermaid
flowchart LR
    OPT["2× Cost Reduction"]
    OPT --> A["INT8 weights\nSmoothQuant or GPTQ\n&lt;0.5% quality loss\n→ 2× memory, 2–4× GEMM"]
    OPT --> B["Continuous batching\nif not already deployed\n→ up to 23× throughput"]
    OPT --> C["Speculative decoding\n7B draft + 70B target\n→ 2–3× decode speedup"]
    OPT --> D["INT8 KV cache\n→ 2× more tokens in flight"]
    A & B & C & D --> E["Combined: 5–8× total\npick based on latency vs throughput priority"]
```

---

---

# GPU KERNELS DEEP DIVE ← Primary Interview Focus

---

## G1. CUDA Thread Hierarchy

```mermaid
flowchart TD
    GRID["Grid\nAll thread blocks for one kernel launch"]

    subgraph SM0 ["Streaming Multiprocessor (SM)"]
        BLK0["Block 0\nUp to 1024 threads\nShared SRAM 164 KB"]
        BLK1["Block 1"]
        BLK2["Block 2"]
    end

    subgraph BLK0_detail ["Block internals"]
        W0["Warp 0\n32 threads — lockstep SIMT"]
        W1["Warp 1\n32 threads"]
        WN["Warp N..."]
        W0 --> T0["Thread 0\nRegisters ~255"]
        W0 --> T1["Thread 1"]
        W0 --> TN["... Thread 31"]
    end

    GRID --> SM0
    SM0 --> BLK0_detail
```

| Resource | A100 limit | Effect when exceeded |
|----------|-----------|---------------------|
| Threads/block | 1024 | Kernel launch fails |
| Registers/thread | 255 | Register spilling to local mem |
| Shared mem/block | 164 KB | Fewer blocks per SM → low occupancy |
| Blocks/SM | 32 | Hard cap |
| Warps/SM | 64 (2048 threads) | Occupancy ceiling |

---

## G2. Warp Execution & Divergence

```mermaid
flowchart LR
    subgraph NoDivergence ["No Divergence — all 32 threads same path"]
        ND1["Thread 0..31\nx = data * 2.0"]
        ND2["All finish same cycle"]
        ND1 --> ND2
    end

    subgraph Divergence ["Divergence — if/else splits warp"]
        D1["Threads 0..15: branch A\nx = data * 2.0"]
        D2["Threads 16..31: branch B\nx = data + 1.0"]
        D3["Warp serializes:\nFirst executes A (16 active, 16 masked)\nThen executes B (16 active, 16 masked)\n→ 2× slower"]
        D1 --> D3
        D2 --> D3
    end
```

**Fix:** use predicated execution — compute both branches, `select` with mask. No branching, no serialization.

---

## G3. Memory Coalescing

```mermaid
flowchart TD
    subgraph Coalesced ["✅ Coalesced — 1 HBM transaction"]
        C_T["Thread i reads A[i]\nAll 32 addresses contiguous\n128 bytes = 1 cache line"]
    end

    subgraph Strided ["❌ Strided — 32 HBM transactions"]
        S_T["Thread i reads A[i × 32]\nAddresses jump by 128 bytes each\n32 separate cache lines"]
    end

    subgraph Gathered ["❌ Random gather — worst case"]
        G_T["Thread i reads A[random[i]]\nUp to 32 separate cache lines\n32× bandwidth cost"]
    end

    HBM["HBM\n2 TB/s peak"]
    Coalesced -->|"1 tx = 128 bytes"| HBM
    Strided -->|"32 tx = 32×128 bytes"| HBM
    Gathered -->|"32 tx scattered"| HBM
```

**Common mistake:** accessing a matrix column-major when it's stored row-major.
**Fix:** transpose the matrix, or use shared memory to do a coalesced load then transpose in SRAM.

---

## G4. Shared Memory Bank Conflicts

```mermaid
flowchart LR
    subgraph Banks ["32 Shared Memory Banks (4 bytes each)"]
        B0["Bank 0"]
        B1["Bank 1"]
        B2["Bank 2"]
        BN["...Bank 31"]
    end

    subgraph Good ["✅ Stride-1: no conflict"]
        G0["Thread 0 → Bank 0"]
        G1["Thread 1 → Bank 1"]
        G2["Thread 2 → Bank 2"]
    end

    subgraph Bad ["❌ Stride-16: 2-way conflict"]
        BAD0["Thread 0 → Bank 0"]
        BAD16["Thread 16 → Bank 0\n→ serialized!"]
    end

    subgraph Broadcast ["✅ Broadcast: no conflict"]
        BR["All 32 threads → same address\n→ multicast, 1 transaction"]
    end

    Good --> Banks
    Bad --> Banks
    Broadcast --> Banks
```

**Element i → bank**: `(i × sizeof(element) / 4) % 32`

---

## G5. Occupancy & Latency Hiding

```mermaid
flowchart TD
    ISSUE["Thread issues HBM load\n~600 cycle latency"]

    subgraph HighOcc ["High Occupancy — many warps resident"]
        W1_HO["Warp 1: waiting for HBM"]
        W2_HO["Warp 2: EXECUTING ← SM switches here"]
        W3_HO["Warp 3: waiting"]
        W4_HO["Warp 4: NEXT"]
        W1_HO --> W2_HO --> W3_HO --> W4_HO
    end

    subgraph LowOcc ["Low Occupancy — few warps resident"]
        W1_LO["Warp 1: waiting for HBM"]
        STALL["SM STALLS — no other warp to run\n→ cycles wasted"]
        W1_LO --> STALL
    end

    ISSUE --> HighOcc
    ISSUE --> LowOcc
```

**Target:** ≥ 50% occupancy for memory-bound kernels. Compute-bound kernels can tolerate lower.

---

## G6. Kernel Fusion — Why It Matters

```mermaid
flowchart LR
    subgraph Unfused ["Unfused (3 separate kernels)"]
        U1["Kernel 1: x = x * 2\nRead HBM → Write HBM"]
        U2["Kernel 2: x = exp(x)\nRead HBM → Write HBM"]
        U3["Kernel 3: x = x / sum(x)\nRead HBM → Write HBM"]
        U1 --> U2 --> U3
    end

    subgraph Fused ["Fused (1 Triton kernel)"]
        F1["Read HBM once"]
        F2["x * 2 → SRAM"]
        F3["exp(x) → SRAM"]
        F4["/ sum → SRAM"]
        F5["Write HBM once"]
        F1 --> F2 --> F3 --> F4 --> F5
    end

    HBM2["HBM\n2 TB/s"]
    Unfused -->|"6 HBM transactions"| HBM2
    Fused -->|"2 HBM transactions\n3× fewer bytes"| HBM2
```

---

## G7. Tiled GEMM — Shared Memory Tiling

```mermaid
flowchart TD
    subgraph Output ["Output tile C[i,j] — BLOCK_M × BLOCK_N"]
        ACC["Accumulator in registers\nFP32 for stability"]
    end

    subgraph KTiles ["Iterate over K tiles"]
        KT1["k=0: Load A[i, 0:BLOCK_K] → SRAM\n     Load B[0:BLOCK_K, j] → SRAM\n     tl.dot(A_tile, B_tile) → acc"]
        KT2["k=1: Load next A, B tiles\n     acc += tl.dot(...)"]
        KTN["... repeat K/BLOCK_K times"]
        KT1 --> KT2 --> KTN --> ACC
    end

    ACC --> WRITE["Write C tile → HBM\nOnce per output tile"]

    note["BLOCK_K=64: arithmetic intensity = BLOCK_K/2 = 32 FLOPs/byte\nAt BLOCK_K=128: 64 FLOPs/byte → near ridge point\ntl.dot automatically uses Tensor Cores on FP16/BF16"]
```

---

## G8. Flash Attention Tiling Algorithm

```mermaid
flowchart TD
    INIT["For each Q block Qi:\nInitialize m_i = -inf, l_i = 0, O_i = 0"]

    subgraph KVLoop ["Loop over K,V blocks (j = 0..N/BLOCK_N)"]
        LOAD["Load Kj, Vj → SRAM"]
        SCORE["S = Qi @ Kj.T × scale\n(BLOCK_N × BLOCK_N, stays in SRAM)"]
        MMAX["m_new = max(m_i, rowmax(S))"]
        EXP["P = exp(S - m_new)\nnumerically stable"]
        LSUM["l_new = exp(m_i - m_new) × l_i + rowsum(P)"]
        UPDATE["O_i = (exp(m_i-m_new) × O_i + P @ Vj)"]
        STATE["m_i, l_i = m_new, l_new"]
        LOAD --> SCORE --> MMAX --> EXP --> LSUM --> UPDATE --> STATE --> LOAD
    end

    FINAL["O_i = O_i / l_i\nWrite to HBM — once!"]

    INIT --> KVLoop --> FINAL
```

**Why no n×n matrix in HBM:** S is computed tile-by-tile inside SRAM and discarded after each iteration. Only O (output) and running stats (m, l) are kept.

---

## G9. Triton Programming Model

```mermaid
flowchart LR
    subgraph Triton ["Triton Kernel Structure"]
        PID["program_id(axis)\n= which tile this instance handles\n(like blockIdx in CUDA)"]
        ARANGE["tl.arange(0, BLOCK_SIZE)\n= element indices for this tile\n(like threadIdx, vectorized)"]
        MASK["mask = offsets < N\nprevents OOB access\nno explicit if/else needed"]
        LOAD["tl.load(ptr + offsets, mask=mask)\nvectorized coalesced HBM read"]
        COMPUTE["Arithmetic on vectors\nauto-vectorized, uses Tensor Cores\nfor tl.dot on fp16/bf16"]
        STORE["tl.store(ptr + offsets, val, mask)\nvectorized coalesced HBM write"]
        PID --> ARANGE --> MASK --> LOAD --> COMPUTE --> STORE
    end
```

**Key Triton ops:**

| Op | What it does |
|----|-------------|
| `tl.load(ptr, mask)` | Vectorized HBM read |
| `tl.store(ptr, val, mask)` | Vectorized HBM write |
| `tl.dot(a, b)` | Tiled matmul → Tensor Cores |
| `tl.max(x, axis)` | Reduction |
| `tl.sum(x, axis)` | Reduction |
| `tl.exp(x)` | Elementwise |
| `tl.program_id(0)` | Which tile |
| `tl.constexpr` | Compile-time constant (tile sizes) |

---

## G10. Kernels You Should Know How to Write

```mermaid
flowchart TD
    KERNELS["Triton Kernels to Master"]

    KERNELS --> SM["Fused Softmax\none HBM read, online stable softmax\nsee 11_gpu_kernels_deep.py"]
    KERNELS --> LN["Fused LayerNorm\nmean+var in one pass\nsee 11_gpu_kernels_deep.py"]
    KERNELS --> GE["Fused GELU\ntanh approximation\nsee 11_gpu_kernels_deep.py"]
    KERNELS --> MM["Tiled GEMM\nSRAM tiling, Tensor Cores via tl.dot\nsee 11_gpu_kernels_deep.py"]
    KERNELS --> FA["Flash Attention Fwd\nonline softmax, O(N) memory\nsee 11_gpu_kernels_deep.py"]
    KERNELS --> RMS["RMSNorm\nsimplified LayerNorm, used in LLaMA\nno mean subtraction"]
    KERNELS --> ROT["RoPE (Rotary Embeddings)\nelementwise rotation on Q,K\nfuse with attention QK proj"]
```

---

## G11. GPU Profiling Workflow

```mermaid
flowchart TD
    SLOW["Kernel is slow / GPU underutilized"]

    SLOW --> NSYS["nsys profile python script.py\nTimeline: see kernel launches,\nmemory copies, sync points"]

    NSYS --> NCU["ncu --set full python script.py\nPer-kernel deep metrics"]

    subgraph Metrics ["Key ncu Metrics"]
        M1["sm__throughput → SM busy %"]
        M2["dram__bytes → HBM traffic"]
        M3["l1tex__t_bytes → L1 traffic"]
        M4["sm__sass_tensor_core_instns → Tensor Core use"]
        M5["Roofline: FLOPs / dram__bytes → memory vs compute bound"]
    end

    NCU --> Metrics

    Metrics --> FIX["Fix based on bottleneck"]

    subgraph Fixes ["Common Fixes"]
        F1["Memory-bound:\nFuse kernels, reduce HBM traffic"]
        F2["Low occupancy:\nReduce register/shared mem pressure\nIncrease BLOCK_SIZE"]
        F3["Bank conflicts:\nPad shared memory arrays"]
        F4["Warp divergence:\nReplace branches with predication"]
        F5["No Tensor Cores:\nUse fp16/bf16, align tiles to 16"]
    end

    FIX --> Fixes
```
