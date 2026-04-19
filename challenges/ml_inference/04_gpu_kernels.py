"""
TOPIC: GPU Kernels — CUDA concepts + Triton hands-on
ByteDance Interview Prep — MLE Inference

ByteDance specifically tests:
  - CUDA memory hierarchy (registers, shared mem, L1/L2, HBM)
  - Kernel fusion and why it matters
  - Tiling / blocking for cache efficiency
  - Writing custom Triton kernels (vectorized ops, flash attention tiles)
  - Roofline model: are you compute-bound or memory-bound?

Run this file:  pip install triton torch && python 04_gpu_kernels.py
"""

import torch
import math


# ═══════════════════════════════════════════════════════════════════════
# SECTION A — CUDA Mental Model (no GPU needed, pure concepts with code)
# ═══════════════════════════════════════════════════════════════════════

"""
GPU Memory Hierarchy (commit this to memory):

  Registers      ~255 per thread    <1 cycle latency    thread-private
  Shared Memory  ~48–164 KB/SM      ~4 cycles           block-shared (SRAM)
  L1 Cache       ~32–128 KB/SM      ~20 cycles          auto-managed
  L2 Cache       ~40–80 MB          ~200 cycles         device-wide
  HBM (VRAM)     ~40–80 GB          ~600 cycles         global memory

Key rule: minimize round trips to HBM. A kernel is memory-bound when
arithmetic intensity (FLOPs / bytes) < hardware peak ratio.

A100 peak:
  FP16 Tensor Core: 312 TFLOPS
  HBM bandwidth:    2000 GB/s
  Arithmetic intensity threshold: 312e12 / 2000e9 = 156 FLOPs/byte
  → Operations below 156 FLOPs/byte are memory-bound (e.g., elementwise ops)
  → Operations above are compute-bound (e.g., large matmuls)
"""


def roofline_analysis(flops: float, bytes_accessed: float,
                      peak_flops=312e12, peak_bw=2000e9):
    """
    Roofline model: tells you if a kernel is compute-bound or memory-bound.

    flops:          arithmetic operations performed
    bytes_accessed: bytes read + written to/from HBM
    """
    intensity = flops / bytes_accessed           # FLOPs per byte
    ridge_point = peak_flops / peak_bw           # device characteristic

    if intensity < ridge_point:
        bottleneck = "MEMORY-BOUND"
        achieved_tflops = intensity * peak_bw / 1e12
    else:
        bottleneck = "COMPUTE-BOUND"
        achieved_tflops = peak_flops / 1e12

    utilization = (achieved_tflops * 1e12) / peak_flops * 100

    print(f"Arithmetic intensity: {intensity:.1f} FLOPs/byte")
    print(f"Ridge point (A100):   {ridge_point:.1f} FLOPs/byte")
    print(f"Bottleneck:           {bottleneck}")
    print(f"Achieved TFLOPS:      {achieved_tflops:.1f}")
    print(f"GPU utilization:      {utilization:.1f}%")
    return bottleneck


# ─────────────────────────────────────────────
# SECTION B — Kernel Fusion (PyTorch)
# ─────────────────────────────────────────────

def unfused_ops(x: torch.Tensor) -> torch.Tensor:
    """Each op is a separate kernel → 3 HBM round trips."""
    x = x * 2.0          # kernel 1: read x, write x
    x = torch.exp(x)     # kernel 2: read x, write x
    x = x / x.sum(-1, keepdim=True)  # kernel 3: read x twice, write x
    return x


@torch.jit.script
def fused_ops(x: torch.Tensor) -> torch.Tensor:
    """
    TorchScript fuses into fewer kernel launches.
    Real fusion: Flash Attention, torch.compile, custom Triton kernels.
    """
    return torch.softmax(x * 2.0, dim=-1)


# ─────────────────────────────────────────────
# SECTION C — Tiled Matrix Multiply (NumPy, mirrors CUDA shared memory tiling)
# ─────────────────────────────────────────────

import numpy as np

def tiled_matmul(A: np.ndarray, B: np.ndarray, tile: int = 32) -> np.ndarray:
    """
    Mirrors CUDA shared-memory tiled GEMM.

    Why tiling?
      Naïve GEMM: each element of C = dot(row_A, col_B)
        → loads A and B from global mem repeatedly (O(n^3) bytes)
      Tiled GEMM: load A-tile and B-tile into shared mem once,
        compute all dot products within the tile
        → (n/tile) fewer HBM reads, much better arithmetic intensity

    CUDA analogy:
      __shared__ float As[TILE][TILE];
      __shared__ float Bs[TILE][TILE];
      for k_tile in range(K // TILE):
          As = A[block_row, k_tile]   # cooperative load
          Bs = B[k_tile, block_col]
          __syncthreads()
          for k in range(TILE): C_local += As[ty,k] * Bs[k,tx]
          __syncthreads()
    """
    M, K = A.shape
    K2, N = B.shape
    assert K == K2
    C = np.zeros((M, N), dtype=A.dtype)

    for i in range(0, M, tile):
        for j in range(0, N, tile):
            for k in range(0, K, tile):
                # These three slices simulate shared memory tiles
                A_tile = A[i:i+tile, k:k+tile]
                B_tile = B[k:k+tile, j:j+tile]
                C[i:i+tile, j:j+tile] += A_tile @ B_tile  # local accumulation
    return C


# ─────────────────────────────────────────────
# SECTION D — Triton Kernel (requires GPU)
# ─────────────────────────────────────────────

TRITON_AVAILABLE = False
try:
    import triton
    import triton.language as tl
    TRITON_AVAILABLE = True
except ImportError:
    pass

if TRITON_AVAILABLE:
    @triton.jit
    def vector_add_kernel(
        X_ptr, Y_ptr, Z_ptr,
        N,
        BLOCK_SIZE: tl.constexpr,
    ):
        """
        Triton kernel: Z = X + Y, element-wise.

        Each program instance handles BLOCK_SIZE elements.
        pid = program ID (like CUDA block index).
        offsets = which elements this block handles.
        mask prevents out-of-bounds for non-multiple-of-BLOCK_SIZE N.
        """
        pid = tl.program_id(axis=0)
        offsets = pid * BLOCK_SIZE + tl.arange(0, BLOCK_SIZE)
        mask = offsets < N

        x = tl.load(X_ptr + offsets, mask=mask)
        y = tl.load(Y_ptr + offsets, mask=mask)
        z = x + y
        tl.store(Z_ptr + offsets, z, mask=mask)

    def triton_vector_add(x: torch.Tensor, y: torch.Tensor) -> torch.Tensor:
        z = torch.empty_like(x)
        N = x.numel()
        BLOCK_SIZE = 1024
        grid = (math.ceil(N / BLOCK_SIZE),)
        vector_add_kernel[grid](x, y, z, N, BLOCK_SIZE=BLOCK_SIZE)
        return z

    @triton.jit
    def softmax_kernel(
        output_ptr, input_ptr,
        input_row_stride, output_row_stride,
        n_cols,
        BLOCK_SIZE: tl.constexpr,
    ):
        """
        Fused online softmax in Triton.
        One program per row. Each program:
          1. Loads the row into SRAM (one HBM read)
          2. Computes max for numerical stability
          3. Computes exp and sum
          4. Normalizes and writes output (one HBM write)
        vs PyTorch unfused: 3 separate kernel launches, 3 HBM reads.
        """
        row_idx = tl.program_id(0)
        row_start = input_ptr + row_idx * input_row_stride
        out_start = output_ptr + row_idx * output_row_stride

        offsets = tl.arange(0, BLOCK_SIZE)
        mask = offsets < n_cols

        row = tl.load(row_start + offsets, mask=mask, other=-float('inf'))

        # Numerically stable: subtract max before exp
        row_max = tl.max(row, axis=0)
        row = row - row_max
        numerator = tl.exp(row)
        denominator = tl.sum(numerator, axis=0)
        softmax_out = numerator / denominator

        tl.store(out_start + offsets, softmax_out, mask=mask)

    def triton_softmax(x: torch.Tensor) -> torch.Tensor:
        assert x.ndim == 2
        rows, cols = x.shape
        BLOCK_SIZE = triton.next_power_of_2(cols)
        output = torch.empty_like(x)
        softmax_kernel[(rows,)](
            output, x,
            x.stride(0), output.stride(0),
            cols,
            BLOCK_SIZE=BLOCK_SIZE,
        )
        return output


# ─────────────────────────────────────────────
# SECTION E — Flash Attention tile logic (CPU sim)
# ─────────────────────────────────────────────

def flash_attention_forward(Q, K, V, block_size=2):
    """
    Flash Attention: tiled online softmax to avoid materializing O(n^2) matrix.

    Key insight: softmax can be computed incrementally with running (max, sum).
    We never need the full n×n attention matrix in memory at once.

    Memory: O(n) instead of O(n^2).

    Q: (n, d)  K: (n, d)  V: (n, d)
    """
    n, d = Q.shape
    O = np.zeros((n, d))          # output accumulator
    l = np.zeros(n)               # running sum of exp
    m = np.full(n, -np.inf)       # running max

    for j in range(0, n, block_size):        # iterate over K,V blocks
        Kj = K[j:j+block_size]
        Vj = V[j:j+block_size]

        for i in range(0, n, block_size):    # iterate over Q blocks
            Qi = Q[i:i+block_size]           # (bs, d) — in "shared memory"
            m_old = m[i:i+block_size].copy()
            l_old = l[i:i+block_size].copy()

            # Compute block scores
            S = Qi @ Kj.T / np.sqrt(d)      # (bs, bs)
            m_new = np.maximum(m_old, S.max(axis=-1))

            # Online softmax update
            exp_S = np.exp(S - m_new[:, None])
            l_new = np.exp(m_old - m_new) * l_old + exp_S.sum(axis=-1)

            # Update output accumulator
            O[i:i+block_size] = (
                np.diag(np.exp(m_old - m_new)) @ O[i:i+block_size]
                + exp_S @ Vj
            )

            m[i:i+block_size] = m_new
            l[i:i+block_size] = l_new

    # Final normalization
    O = O / l[:, None]
    return O


# ─────────────────────────────────────────────
# Demo
# ─────────────────────────────────────────────
if __name__ == "__main__":
    print("=" * 60)
    print("ROOFLINE: Large MatMul (compute-bound)")
    M = N = K = 4096
    flops = 2 * M * N * K
    bytes_io = (M * K + K * N + M * N) * 2  # FP16
    roofline_analysis(flops, bytes_io)

    print()
    print("ROOFLINE: Softmax over 4096-dim vector (memory-bound)")
    n = 4096
    flops_sm = 3 * n          # sub, exp, div
    bytes_sm = 2 * n * 2      # read + write FP16
    roofline_analysis(flops_sm, bytes_sm)

    print()
    print("=" * 60)
    print("TILED MATMUL vs numpy matmul")
    A = np.random.randn(64, 128).astype(np.float32)
    B = np.random.randn(128, 64).astype(np.float32)
    C_tiled = tiled_matmul(A, B, tile=16)
    C_ref = A @ B
    print(f"Max error: {np.abs(C_tiled - C_ref).max():.6f} (should be ~0)")

    print()
    print("=" * 60)
    print("FLASH ATTENTION (CPU simulation)")
    np.random.seed(0)
    n, d = 8, 16
    Q = np.random.randn(n, d)
    K = np.random.randn(n, d)
    V = np.random.randn(n, d)
    out_flash = flash_attention_forward(Q, K, V, block_size=2)
    # Reference: standard attention
    scores = Q @ K.T / np.sqrt(d)
    scores -= scores.max(axis=-1, keepdims=True)
    w = np.exp(scores) / np.exp(scores).sum(axis=-1, keepdims=True)
    out_ref = w @ V
    print(f"Flash vs standard attention max error: {np.abs(out_flash - out_ref).max():.6f}")

    if TRITON_AVAILABLE and torch.cuda.is_available():
        print()
        print("=" * 60)
        print("TRITON KERNELS")
        x = torch.randn(1024, device='cuda')
        y = torch.randn(1024, device='cuda')
        z = triton_vector_add(x, y)
        print(f"Vector add error: {(z - (x+y)).abs().max().item():.6f}")

        x2 = torch.randn(128, 512, device='cuda')
        out_triton = triton_softmax(x2)
        out_torch = torch.softmax(x2, dim=-1)
        print(f"Softmax error: {(out_triton - out_torch).abs().max().item():.6f}")
    else:
        print("\n[Triton kernels skipped — GPU not available]")
        print("Install: pip install triton torch and run on a GPU machine")

"""
INTERVIEW TALKING POINTS:

Q: How does a CUDA kernel launch work?
A: grid(blocks) × block(threads). Each SM runs one or more blocks.
   Threads within a block share SRAM and can __syncthreads().
   Grid is limited by device occupancy (register/shared mem pressure).

Q: What causes low GPU utilization?
A: 1. Memory-bound kernels with low arithmetic intensity (elementwise ops)
   2. Too few threads (small batch size) → SMs idle
   3. Bank conflicts in shared memory
   4. Warp divergence (if-else with different paths)
   5. CPU-GPU sync bubbles (cudaMemcpy, .item() calls)

Q: How does Flash Attention save memory?
A: Tiles Q, K, V into SRAM blocks. Computes exact same result as standard
   attention but never writes the n×n matrix to HBM. O(n) vs O(n^2) memory.
   Also reduces HBM reads by 5-20x → 2-4x wall-clock speedup.

Q: Why use Triton instead of CUDA?
A: Triton abstracts away shared memory management and warp scheduling.
   Write tiled kernels in Python with near-CUDA performance.
   Much faster iteration for ML kernels (fused softmax, attention, GEMM).

Q: What is warp divergence?
A: A warp is 32 threads that execute in lockstep (SIMT).
   If threads take different branches, warp serializes both paths.
   Avoid with predicated execution or sorting inputs to minimize divergence.
"""
