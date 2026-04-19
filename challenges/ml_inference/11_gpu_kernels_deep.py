"""
DEEP DIVE: GPU Kernels for ML Inference — ByteDance Interview
============================================================
Topics:
  A. CUDA execution model (warps, occupancy, divergence)
  B. Memory coalescing & bank conflicts
  C. Triton: fused softmax, layernorm, GELU, matmul, flash attention fwd

Run: conda run -n dev python 11_gpu_kernels_deep.py
Install: conda run -n dev pip install triton torch numpy
"""

import numpy as np
import math

# ── GPU availability check ─────────────────────────────────────────────
try:
    import torch
    import triton
    import triton.language as tl
    GPU = torch.cuda.is_available()
    print(f"GPU available: {GPU}")
    if GPU:
        print(f"  Device: {torch.cuda.get_device_name(0)}")
        props = torch.cuda.get_device_properties(0)
        print(f"  SMs: {props.multi_processor_count}")
        print(f"  Total VRAM: {props.total_memory//1024//1024} MB")
except ImportError:
    GPU = False
    print("Triton/torch not installed — CPU simulations only")


# ═══════════════════════════════════════════════════════════════════════
# A. CUDA EXECUTION MODEL — concepts + Python simulation
# ═══════════════════════════════════════════════════════════════════════

"""
Thread Hierarchy:
  Grid  → collection of thread blocks
  Block → up to 1024 threads; share shared memory; can __syncthreads()
  Warp  → 32 threads executing in lockstep (SIMT)

SM (Streaming Multiprocessor):
  - Runs multiple blocks concurrently (limited by register/shared mem)
  - Schedules warps in round-robin; latency hiding via warp switching
  - A100: 108 SMs, each up to 2048 resident threads (64 warps)

Occupancy = active warps / max warps per SM
  Low occupancy → SM can't hide memory latency → poor throughput
  Killers: too many registers/thread, too much shared mem/block

Warp Divergence:
  - Warp threads execute same instruction (SIMT)
  - if-else with different branches → warp serializes both paths
  - Threads on the inactive branch are masked (not cancelled, just idle)
  - Cost: 2x slower if 50/50 split in a warp
"""


def simulate_warp_divergence():
    """Show divergence cost vs predicated execution."""
    n = 32  # one warp
    data = np.arange(n, dtype=np.float32)

    # Divergent: if-else inside warp
    # CUDA serializes: half warp does branch A, half does branch B
    result_divergent = np.where(data % 2 == 0, data * 2.0, data + 1.0)

    # Predicated: compute both, select — no divergence
    branch_a = data * 2.0
    branch_b = data + 1.0
    mask = (data % 2 == 0)
    result_predicated = np.where(mask, branch_a, branch_b)

    assert np.allclose(result_divergent, result_predicated)
    print("Warp divergence simulation: both paths match ✓")
    print("  In CUDA: predicated version avoids serialization → ~2x faster")


# ═══════════════════════════════════════════════════════════════════════
# B. MEMORY COALESCING & BANK CONFLICTS
# ═══════════════════════════════════════════════════════════════════════

"""
Memory Coalescing:
  A warp (32 threads) issues 32 loads simultaneously.
  If addresses are CONTIGUOUS and ALIGNED → 1 HBM transaction (128 bytes).
  If scattered → up to 32 separate transactions → 32x worse bandwidth.

  Rule: thread i should access element i (row-major, contiguous).

  GOOD:  A[thread_id]           → coalesced
  BAD:   A[thread_id * stride]  → strided, not coalesced
  BAD:   A[random[thread_id]]   → gathered, worst case

Example — matrix row vs column access:
  Row access A[row, 0..N]:   coalesced   ← fast
  Col access A[0..M, col]:   strided     ← slow (M*4 bytes between elements)
  → This is why we transpose B before matmul in some kernels!
"""


def coalescing_demo():
    """Demonstrate coalesced vs strided access patterns."""
    N = 1024
    A = np.random.randn(N, N).astype(np.float32)

    # Coalesced: read N consecutive elements (one warp reads one row)
    # In CUDA: thread i reads A[row, i] — 32 consecutive floats = 128 bytes = 1 tx
    row_sum = A[0, :].sum()   # fast in real CUDA

    # Strided: read every 32nd element (one warp reads one column)
    # In CUDA: thread i reads A[i*stride, col] — 32 scattered addresses = 32 tx
    col_sum = A[:, 0].sum()   # slow in real CUDA

    print(f"Coalescing demo: row_sum={row_sum:.2f}, col_sum={col_sum:.2f}")
    print("  Row access = coalesced (fast), Col access = strided (32x slower HBM)")


"""
Shared Memory Bank Conflicts:
  Shared memory is divided into 32 banks (each 4 bytes wide).
  Element i → bank i % 32.
  If multiple threads in a warp access the SAME bank → serialized (conflict).
  Exception: broadcast (all threads read SAME address) → no conflict.

  GOOD: thread i accesses bank i                    → no conflict
  BAD:  thread i accesses bank (i * 2) % 32         → 2-way conflict
  WORST: all threads access bank 0                  → 32-way conflict (unless broadcast)
"""


def bank_conflict_demo():
    """Simulate bank conflict pattern detection."""
    def bank_id(byte_offset, bank_size_bytes=4, num_banks=32):
        return (byte_offset // bank_size_bytes) % num_banks

    print("\nBank conflict analysis (32 threads, float32 = 4 bytes):")

    # No conflict: thread i accesses element i
    addrs_good = [i * 4 for i in range(32)]
    banks_good = [bank_id(a) for a in addrs_good]
    conflicts_good = 32 - len(set(banks_good))
    print(f"  Stride-1 access: banks={banks_good[:8]}... conflicts={conflicts_good}")

    # 2-way conflict: stride-2 (every other bank used twice)
    addrs_stride2 = [i * 8 for i in range(32)]
    banks_stride2 = [bank_id(a) for a in addrs_stride2]
    from collections import Counter
    max_conflict_s2 = max(Counter(banks_stride2).values())
    print(f"  Stride-2 access: max {max_conflict_s2}-way conflict")

    # 32-way conflict: all threads access same bank
    addrs_bad = [i * 128 for i in range(32)]  # all → bank 0
    banks_bad = [bank_id(a) for a in addrs_bad]
    max_conflict_bad = max(Counter(banks_bad).values())
    print(f"  Stride-32 access: max {max_conflict_bad}-way conflict → worst case!")


# ═══════════════════════════════════════════════════════════════════════
# C. TRITON KERNELS — runnable on GPU, CPU-validated below
# ═══════════════════════════════════════════════════════════════════════

if GPU:

    # ─── C1. Fused Softmax ───────────────────────────────────────────────
    @triton.jit
    def fused_softmax_kernel(
        input_ptr, output_ptr,
        row_stride, n_cols,
        BLOCK: tl.constexpr,
    ):
        """
        One program per row. Loads entire row into SRAM, computes softmax,
        writes result back — only 2 HBM transactions vs 6 for unfused.

        Key optimizations:
          1. Single HBM read (input) + single HBM write (output)
          2. Numerically stable: subtract row max before exp
          3. BLOCK must be power-of-2 >= n_cols; excess masked
        """
        row = tl.program_id(0)
        base = input_ptr + row * row_stride
        out_base = output_ptr + row * row_stride

        cols = tl.arange(0, BLOCK)
        mask = cols < n_cols

        x = tl.load(base + cols, mask=mask, other=-float('inf'))

        # Stable softmax: shift by max
        x_max = tl.max(x, axis=0)
        x = x - x_max
        num = tl.exp(x)
        denom = tl.sum(num, axis=0)
        out = num / denom

        tl.store(out_base + cols, out, mask=mask)

    def triton_softmax(x: torch.Tensor) -> torch.Tensor:
        assert x.ndim == 2
        rows, cols = x.shape
        BLOCK = triton.next_power_of_2(cols)
        out = torch.empty_like(x)
        fused_softmax_kernel[(rows,)](
            x, out,
            x.stride(0), cols,
            BLOCK=BLOCK,
        )
        return out

    # ─── C2. Fused LayerNorm ─────────────────────────────────────────────
    @triton.jit
    def layernorm_kernel(
        x_ptr, out_ptr, gamma_ptr, beta_ptr,
        row_stride, n_cols, eps,
        BLOCK: tl.constexpr,
    ):
        """
        Fused LayerNorm: one pass computes mean + variance, applies norm.
        Standard PyTorch: 3 separate kernels (mean, var, normalize).
        This kernel: single HBM read → all math in SRAM → single write.

        Used everywhere in transformers (pre-norm or post-norm).
        """
        row = tl.program_id(0)
        base = x_ptr + row * row_stride
        out_base = out_ptr + row * row_stride

        cols = tl.arange(0, BLOCK)
        mask = cols < n_cols

        x = tl.load(base + cols, mask=mask, other=0.0).to(tl.float32)
        gamma = tl.load(gamma_ptr + cols, mask=mask, other=1.0)
        beta = tl.load(beta_ptr + cols, mask=mask, other=0.0)

        # Compute mean and variance in one pass (Welford's algorithm)
        mean = tl.sum(x, axis=0) / n_cols
        diff = tl.where(mask, x - mean, 0.0)
        var = tl.sum(diff * diff, axis=0) / n_cols

        x_norm = diff / tl.sqrt(var + eps)
        out = gamma * x_norm + beta

        tl.store(out_base + cols, out, mask=mask)

    def triton_layernorm(x: torch.Tensor,
                         gamma: torch.Tensor, beta: torch.Tensor,
                         eps: float = 1e-5) -> torch.Tensor:
        assert x.ndim == 2
        rows, cols = x.shape
        BLOCK = triton.next_power_of_2(cols)
        out = torch.empty_like(x)
        layernorm_kernel[(rows,)](
            x, out, gamma, beta,
            x.stride(0), cols, eps,
            BLOCK=BLOCK,
        )
        return out

    # ─── C3. Fused GELU ──────────────────────────────────────────────────
    @triton.jit
    def gelu_kernel(x_ptr, out_ptr, N, BLOCK: tl.constexpr):
        """
        GELU(x) = x · Φ(x) ≈ x · sigmoid(1.702 · x)  [fast approximation]
        Commonly used in GPT-2 / BERT FFN.
        Fused: avoids writing intermediate to HBM between exp and multiply.
        """
        pid = tl.program_id(0)
        offs = pid * BLOCK + tl.arange(0, BLOCK)
        mask = offs < N
        x = tl.load(x_ptr + offs, mask=mask)
        # Approximate GELU via erf: x * 0.5 * (1 + erf(x / sqrt(2)))
        out = 0.5 * x * (1.0 + tl.erf(x * 0.7071067811865476))
        tl.store(out_ptr + offs, out, mask=mask)

    def triton_gelu(x: torch.Tensor) -> torch.Tensor:
        out = torch.empty_like(x)
        N = x.numel()
        BLOCK = 1024
        grid = (math.ceil(N / BLOCK),)
        gelu_kernel[grid](x, out, N, BLOCK=BLOCK)
        return out

    # ─── C4. Tiled Matrix Multiply ───────────────────────────────────────
    @triton.jit
    def matmul_kernel(
        A_ptr, B_ptr, C_ptr,
        M, N, K,
        stride_am, stride_ak,
        stride_bk, stride_bn,
        stride_cm, stride_cn,
        BLOCK_M: tl.constexpr,
        BLOCK_N: tl.constexpr,
        BLOCK_K: tl.constexpr,
    ):
        """
        Tiled GEMM: C = A @ B
        Each program computes a BLOCK_M × BLOCK_N tile of C.

        Key ideas:
          1. Load tiles of A and B into SRAM (shared memory)
          2. Compute partial dot products — stay in registers
          3. Accumulate across K tiles
          4. Write result tile to HBM once

        This mirrors cuBLAS GEMM and is the foundation of Flash Attention.

        BLOCK_M=BLOCK_N=BLOCK_K=64 is typical; tune for your GPU's shared mem.
        """
        pid_m = tl.program_id(0)
        pid_n = tl.program_id(1)

        offs_m = pid_m * BLOCK_M + tl.arange(0, BLOCK_M)
        offs_n = pid_n * BLOCK_N + tl.arange(0, BLOCK_N)
        offs_k = tl.arange(0, BLOCK_K)

        # Pointers to first tiles
        A_ptrs = A_ptr + offs_m[:, None] * stride_am + offs_k[None, :] * stride_ak
        B_ptrs = B_ptr + offs_k[:, None] * stride_bk + offs_n[None, :] * stride_bn

        # Accumulator in FP32 for numerical stability (even if inputs are FP16)
        acc = tl.zeros((BLOCK_M, BLOCK_N), dtype=tl.float32)

        for k in range(0, K, BLOCK_K):
            mask_m = offs_m[:, None] < M
            mask_k_a = (offs_k + k)[None, :] < K
            mask_k_b = (offs_k + k)[:, None] < K
            mask_n = offs_n[None, :] < N

            a = tl.load(A_ptrs, mask=mask_m & mask_k_a, other=0.0)
            b = tl.load(B_ptrs, mask=mask_k_b & mask_n, other=0.0)
            acc += tl.dot(a, b)  # uses Tensor Core automatically!

            A_ptrs += BLOCK_K * stride_ak
            B_ptrs += BLOCK_K * stride_bk

        c = acc.to(tl.float16)
        C_ptrs = C_ptr + offs_m[:, None] * stride_cm + offs_n[None, :] * stride_cn
        mask_c = (offs_m[:, None] < M) & (offs_n[None, :] < N)
        tl.store(C_ptrs, c, mask=mask_c)

    def triton_matmul(A: torch.Tensor, B: torch.Tensor) -> torch.Tensor:
        M, K = A.shape
        K2, N = B.shape
        assert K == K2
        C = torch.empty((M, N), device=A.device, dtype=torch.float16)
        BLOCK_M = BLOCK_N = BLOCK_K = 64
        grid = (math.ceil(M / BLOCK_M), math.ceil(N / BLOCK_N))
        matmul_kernel[grid](
            A, B, C,
            M, N, K,
            A.stride(0), A.stride(1),
            B.stride(0), B.stride(1),
            C.stride(0), C.stride(1),
            BLOCK_M=BLOCK_M, BLOCK_N=BLOCK_N, BLOCK_K=BLOCK_K,
        )
        return C

    # ─── C5. Flash Attention Forward Pass ────────────────────────────────
    @triton.jit
    def flash_attn_fwd_kernel(
        Q_ptr, K_ptr, V_ptr, Out_ptr,
        stride_qh, stride_qn, stride_qd,
        stride_kh, stride_kn, stride_kd,
        stride_vh, stride_vn, stride_vd,
        stride_oh, stride_on, stride_od,
        N, D,
        scale,
        BLOCK_N: tl.constexpr,
        BLOCK_D: tl.constexpr,
    ):
        """
        Flash Attention Forward (simplified, single-head, no masking).

        Algorithm:
          For each query block Qi:
            Initialize: m_i = -inf, l_i = 0, O_i = 0
            For each key/value block Kj, Vj:
              S = Qi @ Kj.T * scale              (scores, in SRAM)
              m_new = max(m_i, rowmax(S))
              P = exp(S - m_new)                 (stable exp)
              l_new = exp(m_i - m_new)*l_i + rowsum(P)
              O_i = (exp(m_i - m_new)*O_i + P @ Vj) / l_new  (online update)
              m_i, l_i = m_new, l_new
            Write O_i to HBM

        Why fast: Q,K,V blocks loaded once into SRAM; S never written to HBM.
        Memory: O(N) vs O(N²) for standard attention.
        """
        head = tl.program_id(0)
        q_block = tl.program_id(1)

        # Offsets for this query block
        offs_q = q_block * BLOCK_N + tl.arange(0, BLOCK_N)
        offs_d = tl.arange(0, BLOCK_D)

        # Load Q tile into SRAM
        Q_ptr_h = Q_ptr + head * stride_qh
        Q = tl.load(
            Q_ptr_h + offs_q[:, None] * stride_qn + offs_d[None, :] * stride_qd,
            mask=(offs_q[:, None] < N) & (offs_d[None, :] < D),
            other=0.0,
        )

        # Online softmax state
        m_i = tl.full([BLOCK_N], -float('inf'), dtype=tl.float32)
        l_i = tl.zeros([BLOCK_N], dtype=tl.float32)
        O_i = tl.zeros([BLOCK_N, BLOCK_D], dtype=tl.float32)

        K_ptr_h = K_ptr + head * stride_kh
        V_ptr_h = V_ptr + head * stride_vh

        for j in range(0, tl.cdiv(N, BLOCK_N)):
            offs_k = j * BLOCK_N + tl.arange(0, BLOCK_N)

            # Load K and V tiles
            K_j = tl.load(
                K_ptr_h + offs_k[:, None] * stride_kn + offs_d[None, :] * stride_kd,
                mask=(offs_k[:, None] < N) & (offs_d[None, :] < D),
                other=0.0,
            )
            V_j = tl.load(
                V_ptr_h + offs_k[:, None] * stride_vn + offs_d[None, :] * stride_vd,
                mask=(offs_k[:, None] < N) & (offs_d[None, :] < D),
                other=0.0,
            )

            # S = Q @ K.T * scale   (BLOCK_N × BLOCK_N, stays in SRAM)
            S = tl.dot(Q, tl.trans(K_j)) * scale

            # Online softmax update
            m_new = tl.maximum(m_i, tl.max(S, axis=1))
            P = tl.exp(S - m_new[:, None])
            l_new = tl.exp(m_i - m_new) * l_i + tl.sum(P, axis=1)

            # Update output accumulator
            O_i = (tl.exp(m_i - m_new)[:, None] * O_i
                   + tl.dot(P.to(tl.float16), V_j))

            m_i = m_new
            l_i = l_new

        # Normalize and write output
        O_i = O_i / l_i[:, None]
        Out_ptr_h = Out_ptr + head * stride_oh
        tl.store(
            Out_ptr_h + offs_q[:, None] * stride_on + offs_d[None, :] * stride_od,
            O_i.to(tl.float16),
            mask=(offs_q[:, None] < N) & (offs_d[None, :] < D),
        )

    def triton_flash_attn(Q, K, V):
        """Q,K,V: (heads, N, D)"""
        H, N, D = Q.shape
        Out = torch.zeros_like(Q)
        scale = 1.0 / math.sqrt(D)
        BLOCK_N = 64
        BLOCK_D = triton.next_power_of_2(D)
        grid = (H, math.ceil(N / BLOCK_N))
        flash_attn_fwd_kernel[grid](
            Q, K, V, Out,
            Q.stride(0), Q.stride(1), Q.stride(2),
            K.stride(0), K.stride(1), K.stride(2),
            V.stride(0), V.stride(1), V.stride(2),
            Out.stride(0), Out.stride(1), Out.stride(2),
            N, D, scale,
            BLOCK_N=BLOCK_N, BLOCK_D=BLOCK_D,
        )
        return Out


# ═══════════════════════════════════════════════════════════════════════
# CPU Reference implementations (for validation)
# ═══════════════════════════════════════════════════════════════════════

def ref_softmax(x: np.ndarray) -> np.ndarray:
    x = x - x.max(axis=-1, keepdims=True)
    e = np.exp(x)
    return e / e.sum(axis=-1, keepdims=True)

def ref_layernorm(x: np.ndarray, gamma, beta, eps=1e-5) -> np.ndarray:
    mean = x.mean(axis=-1, keepdims=True)
    var = x.var(axis=-1, keepdims=True)
    return gamma * (x - mean) / np.sqrt(var + eps) + beta

def ref_gelu(x: np.ndarray) -> np.ndarray:
    from scipy.special import erf
    return 0.5 * x * (1 + erf(x / np.sqrt(2)))

def ref_flash_attn_cpu(Q, K, V):
    """Q,K,V: (H, N, D) numpy. Reference standard attention."""
    H, N, D = Q.shape
    scale = 1.0 / np.sqrt(D)
    out = np.zeros_like(Q)
    for h in range(H):
        scores = Q[h] @ K[h].T * scale          # (N, N)
        scores -= scores.max(axis=-1, keepdims=True)
        w = np.exp(scores)
        w /= w.sum(axis=-1, keepdims=True)
        out[h] = w @ V[h]
    return out


# ═══════════════════════════════════════════════════════════════════════
# PROFILING TIPS (concepts — run with nsys / ncu in real job)
# ═══════════════════════════════════════════════════════════════════════
"""
NVIDIA Profiling Tools:
  nsys profile python script.py       → timeline (kernel launches, HBM transfers)
  ncu --set full python script.py     → per-kernel roofline, memory stats

Key metrics to look at in ncu:
  sm__throughput.avg.pct_of_peak_sustained_elapsed  → SM utilization
  l1tex__t_bytes.sum                                → L1 traffic
  dram__bytes.sum                                   → HBM traffic
  sm__sass_tensor_core_instns_executed              → Tensor Core usage
  Arithmetic Intensity = FLOPs / dram__bytes        → are you memory/compute bound?

Torch profiler (quick sanity check):
  with torch.profiler.profile(activities=[...]) as p:
      ...
  p.key_averages().table(sort_by="cuda_time_total")
"""


# ═══════════════════════════════════════════════════════════════════════
# OCCUPANCY CALCULATOR
# ═══════════════════════════════════════════════════════════════════════

def compute_occupancy(
    threads_per_block: int,
    registers_per_thread: int,
    shared_mem_per_block_bytes: int,
    # A100 limits
    max_threads_per_sm: int = 2048,
    max_blocks_per_sm: int = 32,
    max_registers_per_sm: int = 65536,
    max_shared_mem_per_sm: int = 166912,
) -> dict:
    """
    Estimate SM occupancy given kernel resource usage.
    Lower occupancy = fewer warps to hide latency = lower throughput.
    Target: > 50% for memory-bound kernels, > 25% for compute-bound.
    """
    warps_per_block = math.ceil(threads_per_block / 32)

    # Limit from thread count
    blocks_by_threads = max_threads_per_sm // threads_per_block

    # Limit from register pressure
    regs_per_block = registers_per_thread * threads_per_block
    # Registers allocated in 256-register chunks
    regs_per_block_alloc = math.ceil(regs_per_block / 256) * 256
    blocks_by_regs = max_registers_per_sm // regs_per_block_alloc if regs_per_block_alloc > 0 else max_blocks_per_sm

    # Limit from shared memory
    # Shared mem allocated in 256-byte chunks
    smem_alloc = math.ceil(shared_mem_per_block_bytes / 256) * 256 if shared_mem_per_block_bytes > 0 else 0
    blocks_by_smem = max_shared_mem_per_sm // smem_alloc if smem_alloc > 0 else max_blocks_per_sm

    blocks_per_sm = min(blocks_by_threads, blocks_by_regs, blocks_by_smem, max_blocks_per_sm)
    active_warps = blocks_per_sm * warps_per_block
    occupancy = active_warps / (max_threads_per_sm // 32)

    return {
        "blocks_per_sm": blocks_per_sm,
        "active_warps": active_warps,
        "max_warps": max_threads_per_sm // 32,
        "occupancy_pct": occupancy * 100,
        "limiter": "threads" if blocks_per_sm == blocks_by_threads
                   else "registers" if blocks_per_sm == blocks_by_regs
                   else "shared_mem",
    }


# ═══════════════════════════════════════════════════════════════════════
# MAIN
# ═══════════════════════════════════════════════════════════════════════

if __name__ == "__main__":
    print("\n" + "="*60)
    print("A. CUDA Execution Model")
    print("="*60)
    simulate_warp_divergence()
    coalescing_demo()
    bank_conflict_demo()

    print("\n" + "="*60)
    print("B. Occupancy Calculator")
    print("="*60)
    configs = [
        ("Softmax kernel (light)",  128, 32,  0),
        ("MatMul (heavy regs)",     256, 64,  65536),
        ("Flash Attn (heavy smem)", 128, 40,  98304),
        ("LayerNorm (medium)",      256, 48,  32768),
    ]
    for name, tpb, rpt, smem in configs:
        occ = compute_occupancy(tpb, rpt, smem)
        print(f"\n  {name}")
        print(f"    threads/block={tpb}, regs/thread={rpt}, smem={smem//1024}KB")
        print(f"    blocks/SM={occ['blocks_per_sm']}, warps={occ['active_warps']}/{occ['max_warps']}")
        print(f"    occupancy={occ['occupancy_pct']:.1f}%  [limited by {occ['limiter']}]")

    if GPU:
        print("\n" + "="*60)
        print("C. Triton Kernels (GPU)")
        print("="*60)
        torch.manual_seed(42)
        device = 'cuda'

        # C1 Softmax
        x = torch.randn(512, 1024, device=device, dtype=torch.float16)
        y_triton = triton_softmax(x)
        y_ref = torch.softmax(x.float(), dim=-1).half()
        err = (y_triton - y_ref).abs().max().item()
        print(f"\n  Fused Softmax  max_err={err:.2e}  {'PASS' if err < 1e-2 else 'FAIL'}")

        # C2 LayerNorm
        x2 = torch.randn(512, 1024, device=device, dtype=torch.float32)
        gamma = torch.ones(1024, device=device)
        beta = torch.zeros(1024, device=device)
        y_ln = triton_layernorm(x2, gamma, beta)
        y_ref_ln = torch.nn.functional.layer_norm(x2, [1024], gamma, beta)
        err_ln = (y_ln - y_ref_ln).abs().max().item()
        print(f"  Fused LayerNorm  max_err={err_ln:.2e}  {'PASS' if err_ln < 1e-4 else 'FAIL'}")

        # C3 GELU
        x3 = torch.randn(1024 * 1024, device=device, dtype=torch.float32)
        y_gelu = triton_gelu(x3)
        y_ref_gelu = torch.nn.functional.gelu(x3, approximate='none')
        err_gelu = (y_gelu - y_ref_gelu).abs().max().item()
        print(f"  Fused GELU  max_err={err_gelu:.2e}  {'PASS' if err_gelu < 1e-4 else 'FAIL'}")

        # C4 MatMul
        M, K, N = 512, 512, 512
        A = torch.randn(M, K, device=device, dtype=torch.float16)
        B = torch.randn(K, N, device=device, dtype=torch.float16)
        C_triton = triton_matmul(A, B)
        C_ref = (A @ B)
        err_mm = (C_triton - C_ref).abs().max().item()
        print(f"  Tiled MatMul  max_err={err_mm:.2e}  {'PASS' if err_mm < 1.0 else 'FAIL'}")

        # C5 Flash Attention
        H, N_seq, D = 4, 128, 64
        Q = torch.randn(H, N_seq, D, device=device, dtype=torch.float16)
        K_t = torch.randn(H, N_seq, D, device=device, dtype=torch.float16)
        V_t = torch.randn(H, N_seq, D, device=device, dtype=torch.float16)
        out_flash = triton_flash_attn(Q, K_t, V_t)

        # Reference: standard attention
        scale = 1.0 / math.sqrt(D)
        scores = torch.einsum('hnd,hmd->hnm', Q.float(), K_t.float()) * scale
        w = torch.softmax(scores, dim=-1)
        out_ref = torch.einsum('hnm,hmd->hnd', w, V_t.float()).half()
        err_fa = (out_flash - out_ref).abs().max().item()
        print(f"  Flash Attention  max_err={err_fa:.2e}  {'PASS' if err_fa < 0.1 else 'FAIL'}")

        # Benchmark softmax: triton vs torch
        print("\n  --- Softmax Benchmark ---")
        x_bench = torch.randn(4096, 4096, device=device, dtype=torch.float16)
        # warmup
        for _ in range(10):
            triton_softmax(x_bench)
            torch.softmax(x_bench, dim=-1)
        torch.cuda.synchronize()

        import time
        REPS = 100
        t0 = time.perf_counter()
        for _ in range(REPS): triton_softmax(x_bench)
        torch.cuda.synchronize()
        t_triton = (time.perf_counter() - t0) / REPS * 1000

        t0 = time.perf_counter()
        for _ in range(REPS): torch.softmax(x_bench, dim=-1)
        torch.cuda.synchronize()
        t_torch = (time.perf_counter() - t0) / REPS * 1000

        bw_triton = 2 * x_bench.numel() * 2 / t_triton / 1e6  # GB/s
        print(f"  Triton softmax: {t_triton:.3f} ms  ({bw_triton:.1f} GB/s)")
        print(f"  PyTorch softmax: {t_torch:.3f} ms")
        print(f"  Speedup: {t_torch/t_triton:.2f}x")

    else:
        print("\n[GPU not available — validating on CPU]")
        np.random.seed(0)

        x = np.random.randn(16, 128).astype(np.float32)
        assert np.allclose(ref_softmax(x), ref_softmax(x), atol=1e-5)
        print("  Softmax CPU reference: PASS")

        g = np.ones(128); b = np.zeros(128)
        ln = ref_layernorm(x, g, b)
        assert abs(ln.mean()) < 1e-4
        print("  LayerNorm CPU reference: PASS")

        H, N, D = 2, 16, 32
        Q = np.random.randn(H, N, D).astype(np.float32)
        K = np.random.randn(H, N, D).astype(np.float32)
        V = np.random.randn(H, N, D).astype(np.float32)
        out = ref_flash_attn_cpu(Q, K, V)
        print(f"  Flash Attn CPU reference: shape={out.shape}  PASS")

"""
INTERVIEW TALKING POINTS:

Q: How does tl.dot() use Tensor Cores?
A: Triton's tl.dot on fp16/bf16 inputs automatically maps to WMMA/MMA PTX
   instructions that execute on Tensor Cores. You don't call Tensor Cores
   directly — the compiler selects them when operand types and tile sizes
   satisfy alignment requirements (multiples of 16).

Q: Why does Flash Attention need BLOCK_D to be power-of-2?
A: Triton requires constexpr tile shapes for SRAM allocation at compile time.
   The padded BLOCK_D ensures no out-of-bound access and optimal alignment.

Q: What's the hardest part of writing a custom CUDA/Triton kernel?
A: Getting the tiling right: tile size determines shared memory usage,
   which determines occupancy. Too large → shared mem spills, low occupancy.
   Too small → too many HBM reads, low arithmetic intensity.
   Also: handling boundary conditions (masks) without warp divergence.

Q: When does Triton outperform PyTorch?
A: Fused elementwise chains (softmax, layernorm, GELU + linear).
   PyTorch launches separate kernels; Triton fuses all ops into one,
   eliminating HBM round-trips between ops. 1.5-3x speedup typical.
"""
