"""
TOPIC: Distributed Inference — Tensor, Pipeline, Sequence Parallelism
ByteDance Interview Prep — MLE Inference

Key interview questions:
  - What are the three main parallelism strategies?
  - How does tensor parallelism split a transformer layer?
  - What is pipeline parallelism and what is pipeline bubble?
  - When to use which strategy?
  - How does Megatron-LM implement tensor parallelism?
"""

import numpy as np


# ═══════════════════════════════════════════════════════════════════════
# SECTION A — Tensor Parallelism (Megatron-LM style)
# ═══════════════════════════════════════════════════════════════════════
"""
Tensor Parallelism splits individual weight matrices across GPUs.
Two strategies:

1. Column Parallel Linear:
   W (d_model × d_ffn) → [W1 | W2 | ... | Wg]  (split columns)
   Each GPU: Y_i = X @ W_i  (no communication needed)

2. Row Parallel Linear:
   W (d_ffn × d_model) → [W1; W2; ...; Wg]  (split rows)
   Each GPU: Z_i = Y_i @ W_i  then  AllReduce(Z_i) → Z

In a transformer FFN (d_model → 4×d_model → d_model):
  Layer 1: Column Parallel  (no AllReduce)
  Layer 2: Row Parallel     (AllReduce at the end)
  → Only ONE AllReduce per FFN layer per GPU group

Communication cost: AllReduce = 2(N-1)/N * M bytes  where N=GPUs, M=tensor size
"""

def column_parallel_linear(X, W_chunks):
    """
    X: (batch, d_model) — same on all GPUs
    W_chunks: list of (d_model, d_ffn//N) per GPU

    Each GPU computes a slice of the output independently (no comm).
    """
    return [X @ Wc for Wc in W_chunks]


def row_parallel_linear(Y_chunks, W_chunks):
    """
    Y_chunks: list of (batch, d_ffn//N) — split activations
    W_chunks: list of (d_ffn//N, d_model) — split weights

    Each GPU computes partial output, then AllReduce sums them.
    """
    partial_outputs = [Yc @ Wc for Yc, Wc in zip(Y_chunks, W_chunks)]
    # AllReduce = sum across GPUs (simulated here)
    return sum(partial_outputs)  # in real dist: dist.all_reduce(out)


def tensor_parallel_ffn(X, W1, W2, N=4):
    """
    Full FFN with tensor parallelism across N GPUs.
    W1: (d_model, d_ffn)
    W2: (d_ffn, d_model)
    """
    d_ffn = W1.shape[1]
    chunk = d_ffn // N

    W1_chunks = [W1[:, i*chunk:(i+1)*chunk] for i in range(N)]
    W2_chunks = [W2[i*chunk:(i+1)*chunk, :] for i in range(N)]

    # Forward pass
    Y_chunks = column_parallel_linear(X, W1_chunks)    # no comm
    Y_chunks = [np.maximum(0, y) for y in Y_chunks]    # ReLU locally
    Z = row_parallel_linear(Y_chunks, W2_chunks)        # AllReduce here
    return Z


# ═══════════════════════════════════════════════════════════════════════
# SECTION B — Pipeline Parallelism
# ═══════════════════════════════════════════════════════════════════════
"""
Pipeline Parallelism: split model layers across GPUs (each GPU owns a "stage").
  GPU 0: layers 0–7
  GPU 1: layers 8–15
  GPU 2: layers 16–23
  GPU 3: layers 24–31

Communication: send/receive activations between adjacent stages (point-to-point).
Latency: pipeline bubble = (N_gpus - 1) / N_microbatches

Bubble ratio = (p-1) / (m + p - 1)  where p=pipeline stages, m=microbatches
  → with m >> p, bubble is negligible

GPipe: simple, large bubble (B/N)
1F1B (Megatron): interleaved 1-forward-1-backward, bubble = 1/m
Interleaved stages (Megatron v2): further reduces bubble
"""

class PipelineStage:
    """Simulates one stage of a pipeline parallel model."""
    def __init__(self, stage_id, num_layers_per_stage, d_model):
        self.stage_id = stage_id
        self.num_layers = num_layers_per_stage
        self.d_model = d_model
        self.weights = [np.random.randn(d_model, d_model) * 0.01
                        for _ in range(num_layers_per_stage)]

    def forward(self, x):
        """Process microbatch through this stage's layers."""
        for W in self.weights:
            x = np.tanh(x @ W)
        return x


def pipeline_forward(microbatches, stages):
    """
    GPipe-style pipeline: process each microbatch through all stages.
    Real implementation uses async sends/receives (NCCL P2P).
    """
    results = []
    for mb in microbatches:
        x = mb
        for stage in stages:
            x = stage.forward(x)
        results.append(x)
    return results


def bubble_ratio(num_stages, num_microbatches):
    return (num_stages - 1) / (num_microbatches + num_stages - 1)


# ═══════════════════════════════════════════════════════════════════════
# SECTION C — Sequence Parallelism
# ═══════════════════════════════════════════════════════════════════════
"""
Sequence Parallelism (Megatron-LM v3):
  - Tensor parallelism requires keeping full-sequence activations on each GPU
    → activations are (batch, seq_len, d_model) — duplicated N times
  - Sequence parallelism splits activations along the sequence dimension
  - Reduces activation memory by factor N
  - Used with Tensor Parallelism: AllGather before TP region, ReduceScatter after

Combined: Tensor + Sequence Parallelism
  LayerNorm → ReduceScatter (split seq) → Attention/FFN (TP) → AllGather (merge seq)
"""

# ═══════════════════════════════════════════════════════════════════════
# SECTION D — When to use what
# ═══════════════════════════════════════════════════════════════════════
"""
Decision Guide for Distributed Inference:

Model size → #GPUs:
  ≤ 7B params   → 1 GPU (fit in 24 GB with INT8/INT4)
  7B–70B params → 2–8 GPUs, tensor parallelism (within a node, NVLink)
  > 70B params  → tensor + pipeline parallelism (across nodes, InfiniBand)

Latency priority (online serving):
  → Prefer Tensor Parallelism (lower per-token latency)
  → Keep N small (2-4 GPUs) to reduce AllReduce latency

Throughput priority (offline batch):
  → Data Parallelism first (independent copies)
  → Pipeline Parallelism for models that don't fit one GPU

Key bandwidth numbers:
  NVLink A100: 600 GB/s bidirectional (within node)
  InfiniBand 400G: 50 GB/s (across nodes)
  PCIe 4.0: 32 GB/s (CPU-GPU or peer-GPU without NVLink)

AllReduce cost for tensor parallelism:
  2 * (N-1)/N * M bytes → for N=8, M=1GB: ~1.75 GB per AllReduce
  At 600 GB/s: ~3ms — manageable for large d_model
"""


# ─────────────────────────────────────────────
# Demo
# ─────────────────────────────────────────────
if __name__ == "__main__":
    np.random.seed(42)
    batch, d_model, d_ffn = 4, 64, 256

    X = np.random.randn(batch, d_model)
    W1 = np.random.randn(d_model, d_ffn) * 0.1
    W2 = np.random.randn(d_ffn, d_model) * 0.1

    print("=== Tensor Parallel FFN ===")
    out_tp = tensor_parallel_ffn(X, W1, W2, N=4)
    out_ref = np.maximum(0, X @ W1) @ W2    # single-GPU reference
    print(f"Output shape: {out_tp.shape}")
    print(f"Max error vs reference: {np.abs(out_tp - out_ref).max():.6f}")

    print("\n=== Pipeline Parallelism ===")
    stages = [PipelineStage(i, num_layers_per_stage=2, d_model=d_model) for i in range(4)]
    microbatches = [np.random.randn(2, d_model) for _ in range(8)]
    results = pipeline_forward(microbatches, stages)
    print(f"Processed {len(results)} microbatches, output shape: {results[0].shape}")

    print("\n=== Pipeline Bubble Analysis ===")
    for m in [1, 4, 8, 16, 32]:
        b = bubble_ratio(num_stages=4, num_microbatches=m)
        print(f"  stages=4, microbatches={m:2d}: bubble ratio = {b:.3f} ({b*100:.1f}%)")

"""
INTERVIEW TALKING POINTS:

Q: What is the pipeline bubble and how to reduce it?
A: Bubble = idle time when a stage waits for the previous stage.
   With p stages and m microbatches: bubble_fraction = (p-1)/(m+p-1).
   Reduce by: more microbatches, interleaved scheduling (1F1B), or fewer stages.

Q: Why is tensor parallelism preferred for low-latency serving?
A: Pipeline parallelism adds stage-to-stage latency at each layer boundary.
   Tensor parallelism splits work within each layer — all GPUs active simultaneously.
   For a 2-4 GPU split within an NVLink node, AllReduce latency is <5ms.

Q: What is the Megatron-LM 3D parallelism?
A: Data Parallel × Tensor Parallel × Pipeline Parallel.
   e.g., 512 GPUs: 8 TP × 8 PP × 8 DP
   DP: independent data replicas (most scalable)
   TP: within a node (NVLink bandwidth)
   PP: across nodes (point-to-point, less bandwidth needed)
"""
