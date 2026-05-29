"""
TOPIC: Quantization — INT8, FP16, INT4
ByteDance Interview Prep — MLE Inference

Key interview questions:
  - What is quantization and why does it matter for inference?
  - Difference between PTQ and QAT?
  - What is absmax vs zero-point quantization?
  - How does per-channel vs per-tensor quantization differ?
  - What is GPTQ / AWQ?
"""

import numpy as np


# ─────────────────────────────────────────────
# 1. Absmax (symmetric) quantization — INT8
# ─────────────────────────────────────────────
def quantize_absmax(x: np.ndarray, bits: int = 8):
    """
    Maps [-max_val, max_val] → [-127, 127] (INT8 symmetric).
    Scale = max_abs / 127
    No zero-point needed (symmetric around 0).
    """
    max_val = np.abs(x).max()
    scale = max_val / (2 ** (bits - 1) - 1)
    x_q = np.round(x / scale).astype(np.int8)
    return x_q, scale


def dequantize_absmax(x_q: np.ndarray, scale: float):
    return x_q.astype(np.float32) * scale


# ─────────────────────────────────────────────
# 2. Zero-point (asymmetric) quantization
# ─────────────────────────────────────────────
def quantize_zeropoint(x: np.ndarray, bits: int = 8):
    """
    Maps [min_val, max_val] → [0, 255] (UINT8).
    Handles asymmetric distributions (e.g., ReLU outputs — all non-negative).
    scale = (max - min) / 255
    zero_point = round(-min / scale)
    """
    x_min, x_max = x.min(), x.max()
    n_levels = 2 ** bits - 1
    scale = (x_max - x_min) / n_levels
    zero_point = int(np.round(-x_min / scale))
    x_q = np.clip(np.round(x / scale) + zero_point, 0, n_levels).astype(np.uint8)
    return x_q, scale, zero_point


def dequantize_zeropoint(x_q: np.ndarray, scale: float, zero_point: int):
    return (x_q.astype(np.float32) - zero_point) * scale


# ─────────────────────────────────────────────
# 3. Per-channel vs per-tensor
# ─────────────────────────────────────────────
def quantize_per_channel(W: np.ndarray, bits: int = 8):
    """
    Per-channel: each output neuron gets its own scale.
    Much better accuracy than per-tensor for weights (especially after GELU/SwiGLU).
    """
    scales = np.abs(W).max(axis=1, keepdims=True) / (2 ** (bits - 1) - 1)
    W_q = np.round(W / scales).astype(np.int8)
    return W_q, scales.squeeze()


# ─────────────────────────────────────────────
# 4. Quantized matrix multiply
# ─────────────────────────────────────────────
def quantized_matmul(x_q, W_q, x_scale, W_scales):
    """
    INT8 matmul: compute in int32, rescale to float32 at output.
    On real hardware this is done by CUDA INT8 GEMM (cublasSgemmEx / cuBLAS).
    """
    # Accumulate in int32 to avoid overflow
    out_int32 = x_q.astype(np.int32) @ W_q.T.astype(np.int32)
    # Rescale: combined scale = x_scale * W_scales (per-output-channel)
    return out_int32.astype(np.float32) * x_scale * W_scales


# ─────────────────────────────────────────────
# 5. GPTQ intuition (weight-only INT4)
# ─────────────────────────────────────────────
"""
GPTQ Algorithm (Frantar et al. 2022):
  - Quantize weights layer by layer, one column at a time
  - After quantizing column i, update remaining columns to compensate:
      W[:, j] -= (err_i / H_inv[i,i]) * H_inv[i, j]   for j > i
    where H = X^T X (Hessian of the squared error w.r.t. weights)
  - Compensating for errors greedily keeps model accuracy very close to FP16
  - Achieves 3-4 bits per weight with <1% perplexity degradation on LLaMA

AWQ (Activation-aware Weight Quantization):
  - Observation: only ~1% of weights matter most (high activation magnitude channels)
  - Scale those channels up before quantizing, scale activations down to compensate
  - No need for Hessian computation, faster than GPTQ, similar accuracy
"""


# ─────────────────────────────────────────────
# Demo
# ─────────────────────────────────────────────
if __name__ == "__main__":
    np.random.seed(42)

    # ── Symmetric INT8 ──
    x = np.random.randn(4, 8).astype(np.float32) * 3.0
    x_q, scale = quantize_absmax(x)
    x_recon = dequantize_absmax(x_q, scale)
    print("=== Absmax INT8 ===")
    print(f"Original: {x[0]}")
    print(f"Quantized (INT8): {x_q[0]}")
    print(f"Reconstructed: {x_recon[0]}")
    print(f"Max error: {np.abs(x - x_recon).max():.4f}")
    print(f"Memory: FP32={x.nbytes}B → INT8={x_q.nbytes}B (4x reduction)")

    # ── Asymmetric UINT8 (e.g., post-ReLU activations) ──
    print("\n=== ZeroPoint UINT8 ===")
    x_pos = np.abs(np.random.randn(4, 8).astype(np.float32))  # all positive
    x_q2, s2, zp = quantize_zeropoint(x_pos)
    x_recon2 = dequantize_zeropoint(x_q2, s2, zp)
    print(f"Original range: [{x_pos.min():.3f}, {x_pos.max():.3f}]")
    print(f"Max error: {np.abs(x_pos - x_recon2).max():.4f}")

    # ── Per-channel weights ──
    print("\n=== Per-Channel Weights ===")
    W = np.random.randn(16, 8).astype(np.float32)
    W_q, W_scales = quantize_per_channel(W)
    W_recon = W_q.astype(np.float32) * W_scales[:, None]
    print(f"Per-channel scales shape: {W_scales.shape}")
    print(f"Max error: {np.abs(W - W_recon).max():.4f}")

    # ── Quantized matmul ──
    print("\n=== Quantized MatMul ===")
    x_f = np.random.randn(2, 8).astype(np.float32)
    x_q3, x_s = quantize_absmax(x_f)
    W_q2, W_s = quantize_per_channel(W)
    out_q = quantized_matmul(x_q3, W_q2, x_s, W_s)
    out_fp = x_f @ W.T
    print(f"FP32 output[0]: {out_fp[0, :4]}")
    print(f"INT8 output[0]: {out_q[0, :4]}")
    print(f"Max error: {np.abs(out_fp - out_q).max():.4f}")

"""
INTERVIEW TALKING POINTS:

Q: PTQ vs QAT?
A: PTQ (Post-Training Quantization): quantize after training, no retraining needed.
   Fast but loses accuracy on lower bits (<4 bit).
   QAT (Quantization-Aware Training): simulate quantization noise during training.
   More accurate at low bits but expensive (full training run).

Q: Why INT8 inference is faster?
A: 1) INT8 GEMM has 2-4x higher throughput than FP32 on modern GPUs
   2) 4x smaller memory → fits more KV cache, larger batch size
   3) Less HBM bandwidth → faster for memory-bound decode phase

Q: Where does quantization hurt most?
A: - Outlier activations (LLMs have extreme activation values in some channels)
   - Attention weights (fine-grained distributions hard to quantize uniformly)
   - First/last layers (embedding + LM head — quantize separately or keep in FP16)

Q: What is LLM.int8() (bitsandbytes)?
A: Decomposes matrix into two parts: outlier channels (FP16) + rest (INT8).
   Avoids accuracy loss from outliers while still getting most of INT8 speedup.
"""
