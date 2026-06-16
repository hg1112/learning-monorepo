# Floating Point Precision — fp32, fp16, bf16

## What is Floating Point?

Numbers in a computer are stored in binary with three parts:

```
fp32 (32 bits = 4 bytes)
┌──┬──────────┬───────────────────────┐
│S │ Exponent │      Mantissa         │
│1 │  8 bits  │      23 bits          │
└──┴──────────┴───────────────────────┘

fp16 (16 bits = 2 bytes)
┌──┬──────┬───────────┐
│S │ Exp  │ Mantissa  │
│1 │ 5 b  │  10 bits  │
└──┴──────┴───────────┘

bf16 (16 bits = 2 bytes — "brain float")
┌──┬──────────┬──────┐
│S │ Exponent │ Mant │
│1 │  8 bits  │ 7 b  │
└──┴──────────┴──────┘
```

- **More bits = more precision** (can represent more decimal places)
- **Fewer bits = less memory + faster on specialized hardware**

---

## fp32 vs fp16 vs bf16 Comparison

| Format | Bits | Memory per param | Range | Precision |
|--------|------|-----------------|-------|-----------|
| fp32 | 32 | 4 bytes | ±3.4 × 10³⁸ | ~7 decimal digits |
| fp16 | 16 | 2 bytes | ±65504 | ~3 decimal digits |
| bf16 | 16 | 2 bytes | ±3.4 × 10³⁸ | ~2 decimal digits |

**Key insight:** fp16 has a small range (max value 65504). Large intermediate values during training can overflow → `NaN`. This is less of a problem during inference since you're not computing gradients.

**bf16** keeps fp32's wide range but reduces mantissa precision — safer than fp16 for training, but requires Ampere+ GPUs (RTX 3000+).

---

## Why fp16 Matters for Inference

### Memory impact
A Stable Diffusion v1.5 UNet has ~860M parameters:

```
fp32:  860M × 4 bytes = 3.44 GB
fp16:  860M × 2 bytes = 1.72 GB   ← half the VRAM
```

On your 6GB GTX 1660 Ti, fp32 SD v1.5 barely fits. fp16 gives you headroom for activations.

### Speed impact — Tensor Cores

NVIDIA GPUs starting from Volta (V100) have **Tensor Cores**: specialized hardware units that do fp16 matrix multiplications in a single clock cycle instead of many.

```
Standard CUDA Core (fp32):
  Multiply + Add = 2 operations, 2 clock cycles

Tensor Core (fp16):
  4×4 matrix multiply-accumulate = 1 operation, 1 clock cycle
  → 8× higher throughput for matrix ops
```

The UNet is almost entirely matrix multiplications (attention layers, convolutions). fp16 = 1.5-2× faster on Tensor Core GPUs.

**GTX 1660 Ti has Turing Tensor Cores** → fp16 is faster than fp32 on your machine.

---

## Why fp16 CRASHES on CPU

CPUs have no Tensor Core equivalent. When you try fp16 on CPU:

1. PyTorch has no optimized CPU fp16 kernel for many ops
2. Falls back to software emulation (slow)
3. Some ops (like `LayerNorm`) have no CPU fp16 path at all → `RuntimeError`

This is why you wrote:
```python
dtype = torch.float16 if device == "cuda" else torch.float32
```

**Always guard fp16 with a device check.**

---

## How to Load a Model in fp16

```python
from diffusers import DiffusionPipeline
import torch

pipe = DiffusionPipeline.from_pretrained(
    "runwayml/stable-diffusion-v1-5",
    torch_dtype=torch.float16,   # loads weights directly in fp16
)
pipe = pipe.to("cuda")
```

`torch_dtype=torch.float16` tells Diffusers to load the checkpoint weights already cast to fp16 — saves the time and peak memory of loading fp32 then casting.

---

## Checking Precision in Practice

```python
# Check what dtype a model's parameters are stored in
for name, param in pipe.unet.named_parameters():
    print(name, param.dtype)
    break  # just check the first one

# Check a tensor's dtype
tensor = torch.randn(3, 3)
print(tensor.dtype)  # torch.float32

tensor_half = tensor.half()  # or tensor.to(torch.float16)
print(tensor_half.dtype)  # torch.float16
```

---

## Interview Answer: "Why do you use fp16?"

> "fp16 halves VRAM usage — a 4GB fp32 model becomes 2GB, which either fits in VRAM where it didn't before, or leaves more headroom for activations and larger batch sizes. On GPUs with Tensor Cores, fp16 matrix multiplications are also 1.5-2× faster than fp32. The tradeoff is reduced numerical range, but for inference — where we're just doing forward passes, no gradient accumulation — this rarely causes issues. I guard it with a device check because CPU has no fp16 hardware support and will either crash or be slower than fp32."
