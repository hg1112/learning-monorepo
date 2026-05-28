# ByteDance ML Inference — Interview Reference

---

## 1. Recommendation System (TikTok-scale)

### Multi-Stage Pipeline

```mermaid
flowchart TD
    U([User Request]) --> R
    subgraph R[Retrieval — billions → 1K]
        R1[Two-Tower ANN]
        R2[CF / Item-Item]
        R3[Trending Pool]
        R4[Social Graph]
    end
    R --> PR
    subgraph PR[Pre-Ranking — 1K → 200]
        PR1[Lightweight DCN / GBDT\nINT8 quantized]
    end
    PR --> RK
    subgraph RK[Ranking — 200 → 50]
        RK1[DCN v2 + Multi-task heads\npCTR · pLT · pLike · pShare]
    end
    RK --> RR
    subgraph RR[Re-Ranking]
        RR1[Diversity - MMR]
        RR2[Freshness boost]
        RR3[Business rules / Ads]
        RR4[Exploration ε-greedy]
    end
    RR --> F([Feed])
```

---

### Two-Tower Retrieval Model

```mermaid
flowchart LR
    subgraph UT[User Tower]
        U1[user_id\nhistory\ndemographics] --> UE[MLP] --> UV[256-d vector]
    end
    subgraph IT[Item Tower]
        I1[item_id\ncategory\ncontent emb] --> IE[MLP] --> IV[256-d vector]
    end
    UV -->|dot product| S[Score]
    IV -->|dot product| S
    S --> ANN[HNSW / FAISS\nANN Index]
    ANN --> K[Top-K candidates]

    style UV fill:#4a9eff,color:#fff
    style IV fill:#ff7a4a,color:#fff
```

**Training:** In-batch negatives + hard negatives, contrastive loss.  
**Online:** Encode user real-time → ANN lookup <10ms.  
**Offline:** Re-embed all items every few hours, rebuild index.

---

### DCN v2 (Ranking Model Architecture)

```mermaid
flowchart TD
    IN[Input: user · item · context · cross features] --> EMB[Embedding Layer\nsparse → dense]
    EMB --> CN[Cross Network\nexplicit polynomial interactions]
    EMB --> DN[Deep Network\nMLP — implicit interactions]
    CN --> CAT[Concatenate]
    DN --> CAT
    CAT --> CTR[pCTR head]
    CAT --> LT[pLongView head]
    CAT --> LK[pLike head]
    CTR & LT & LK --> SCORE[Weighted blend → final score]
```

---

### Feature Store Architecture

```mermaid
flowchart LR
    subgraph Offline
        SP[Spark jobs\nbatch features] --> HV[Hive / Parquet]
    end
    subgraph Online
        KF[Kafka stream\nevent features] --> FL[Flink consumer]
    end
    HV --> FS[(Feature Store\nRedis Cluster\nsub-1ms lookup)]
    FL --> FS
    FS --> RS[Ranking Server]
    RS --> MODEL[DCN v2 Inference]
```

---

### Key Latency Targets

| Stage | Latency | Candidates In/Out |
|-------|---------|-------------------|
| Retrieval | <10ms | 100B → 1K |
| Pre-rank | <5ms | 1K → 200 |
| Rank | <30ms | 200 → 50 |
| Re-rank | <5ms | 50 → feed |
| **Total P99** | **<100ms** | |

---

---

## 2. LLM Inference System Design

### Prefill vs Decode Phases

```mermaid
flowchart LR
    subgraph PF[PREFILL — compute-bound]
        P1[Full prompt processed\nin parallel\none forward pass] --> KV[KV Cache populated]
    end
    subgraph DC[DECODE — memory-bandwidth-bound]
        D1[1 token generated\nper forward pass\nautoregressive]
    end
    KV --> D1
    D1 -->|loop| D1

    style PF fill:#2d6a4f,color:#fff
    style DC fill:#6b2d6a,color:#fff
```

---

### KV Cache & PagedAttention

```mermaid
flowchart TD
    subgraph NAIVE[Naive KV Cache — fragmented]
        S1[Seq A — 1024 tok\nreserved upfront]
        S2[Seq B — 512 tok\nreserved upfront]
        S3[GAP — wasted memory]
    end

    subgraph PAGED[PagedAttention — vLLM]
        P1[Page 0\n16 tok] --> P2[Page 3\n16 tok] --> P5[Page 7\n16 tok]
        P6[Page 1\n16 tok] --> P7[Page 4\n16 tok]
        NOTE[Physical pages allocated\non demand via page table\nnear-zero fragmentation]
    end

    NAIVE -->|"2-4x throughput\nimprovement"| PAGED
```

**Memory formula:**  
`KV size = 2 × layers × seq_len × hidden_dim × bytes/element`  
LLaMA-70B, 4K ctx, FP16 ≈ **~35 GB per request** without paging.

---

### Continuous Batching

```mermaid
gantt
    title Static vs Continuous Batching
    dateFormat X
    axisFormat %s

    section Static Batch
    Seq A (long)   : 0, 10
    Seq B (short)  : 0, 4
    Seq C (medium) : 0, 7
    GPU idle (B,C done) : crit, 4, 10

    section Continuous Batch
    Seq A          : 0, 10
    Seq B          : 0, 4
    Seq D joins    : 4, 8
    Seq C          : 0, 7
    Seq E joins    : 7, 10
```

New requests slot in the moment a sequence finishes — GPU stays fully utilized.

---

### Speculative Decoding

```mermaid
sequenceDiagram
    participant D as Draft Model (7B — fast)
    participant T as Target Model (70B — accurate)

    D->>D: Generate tok1, tok2, tok3, tok4 speculatively
    D->>T: Submit all 4 draft tokens
    T->>T: Verify all 4 in ONE forward pass (parallel)
    T->>D: ✓tok1  ✓tok2  ✗tok3 (reject)
    Note over T,D: Accept tok1,tok2. Resample tok3. Discard tok4.
    Note over D,T: Net gain: 2 tokens in cost of ~1 target pass → 2-3x speedup
```

---

### Quantization Tradeoffs

```mermaid
quadrantChart
    title Quantization: Speed vs Quality
    x-axis Low Speed --> High Speed
    y-axis Low Quality --> High Quality
    quadrant-1 Best of both
    quadrant-2 High quality slow
    quadrant-3 Fast but lossy
    quadrant-4 Balanced
    FP16: [0.2, 0.95]
    INT8 W8A8: [0.55, 0.88]
    FP8 H100: [0.6, 0.9]
    AWQ 4-bit: [0.8, 0.75]
    GPTQ 4-bit: [0.8, 0.65]
```

---

### Tensor vs Pipeline Parallelism

```mermaid
flowchart TD
    subgraph TP[Tensor Parallelism — split within a layer]
        G1[GPU 0\nW_cols 0..N/2] 
        G2[GPU 1\nW_cols N/2..N]
        G1 & G2 -->|AllReduce| OUT[Output]
    end

    subgraph PP[Pipeline Parallelism — split across layers]
        L1[GPU 0\nLayers 0-15] -->|activations| L2[GPU 1\nLayers 16-31]
        L2 -->|activations| L3[GPU 2\nLayers 32-47]
        L3 -->|activations| L4[GPU 3\nLayers 48-63]
    end

    style TP fill:#1a3a5c,color:#fff
    style PP fill:#3a1a5c,color:#fff
```

- **TP:** reduces per-request latency, needs fast NVLink intra-node  
- **PP:** scales to very large models, micro-batching hides bubble overhead

---

### PD Disaggregation (ByteDance scale)

```mermaid
flowchart LR
    LB[Load Balancer] --> PW
    subgraph PW[Prefill Workers\nhigh-compute GPUs]
        P1[Prefill req 1]
        P2[Prefill req 2]
    end
    PW -->|KV cache transfer\nRDMA / NIXL| DW
    subgraph DW[Decode Workers\nhigh-HBM-BW GPUs]
        D1[Decode stream 1]
        D2[Decode stream 2]
    end
    DW --> OUT[Streamed tokens]

    style PW fill:#1a5c3a,color:#fff
    style DW fill:#5c3a1a,color:#fff
```

Scale each phase independently based on workload mix.

---

### Full LLM Serving Stack

```mermaid
flowchart TD
    CLIENT[Client requests] --> LB[Load Balancer\nroute by length / type]
    LB --> PF[Prefill Pods]
    PF -->|KV transfer RDMA| DC[Decode Pods]
    DC --> STREAM[Token stream to client]

    subgraph OPT[Optimizations per layer]
        CB[Continuous Batching]
        PA[PagedAttention]
        FA[FlashAttention]
        SD[Speculative Decoding]
        Q[Quantization INT8/FP8]
        PC[Prefix Cache\nshared system prompts]
    end

    DC -.uses.- OPT
```

---

### Key LLM Serving Metrics

| Metric | Definition | Target |
|--------|------------|--------|
| TTFT | Time to first token (prefill) | <500ms |
| TPOT | Time per output token | <50ms |
| Throughput | Tokens/sec system-wide | maximize |
| MFU | Model FLOP Utilization | >50% good |
| KV Cache hit rate | Prefix cache reuse | >60% in prod |

---

## ByteDance-Specific Angles

- Ranking uses **MMOE** (Multi-gate Mixture of Experts) for multi-task learning
- **Volcano Engine** = their internal cloud running custom inference infra
- **Prefix caching**: same system prompt across millions of DouBao requests → huge KV reuse
- **Disaggregated KV store** for long-context and multi-turn conversations
- GBDT/XGBoost still runs in pre-ranking — not everything is deep learning
- Isotonic regression used for **calibration** of pCTR → actual probability
