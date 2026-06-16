# Inference Optimizations

## Overview — The Optimization Stack

Apply these in order of impact. Each one is independent — you can use all of them together.

```
┌─────────────────────────────────────────────────────────────┐
│                   Optimization Stack                        │
│                                                             │
│  1. fp16          ████████████████████  highest ROI        │
│     2× VRAM reduction + 1.5× speed                         │
│                                                             │
│  2. torch.compile ██████████████        20-40% speed       │
│     Fuses ops, reduces Python overhead                      │
│                                                             │
│  3. Attention     ████████              VRAM reduction      │
│     Slicing       Slight speed cost                         │
│                                                             │
│  4. VAE Tiling    ████                  Enables large res   │
│     Slight overhead at normal res                           │
│                                                             │
│  5. Quantization  ██████████████████    2-4× VRAM          │
│     (INT8/INT4)   Quality degradation                       │
└─────────────────────────────────────────────────────────────┘
```

---

## 1. fp16 (Half Precision)

**What:** Load and run model weights in 16-bit instead of 32-bit floats.

```python
pipe = DiffusionPipeline.from_pretrained(
    "runwayml/stable-diffusion-v1-5",
    torch_dtype=torch.float16,   # ← load directly in fp16
)
```

**Impact:**
- VRAM: -50% (4 GB → 2 GB for SD v1.5)
- Speed: +30-50% on Tensor Core GPUs (GTX 1660 Ti, RTX series)
- Risk: Slightly reduced numerical precision (rarely a problem for inference)

**When NOT to use:** CPU inference (no hardware support, will crash or be slower).

---

## 2. `torch.compile`

**What:** PyTorch traces your model's computation graph and compiles it to optimized CUDA kernels, fusing multiple ops into single GPU kernel launches.

```python
# Without compile: Python calls one GPU kernel per op
# matmul → kernel launch
# add    → kernel launch  (overhead per launch: ~5-10 μs)
# gelu   → kernel launch

# With compile: fused into one kernel
# matmul + add + gelu → single kernel launch (pay overhead once)

pipe.unet = torch.compile(pipe.unet, mode="reduce-overhead", fullgraph=True)
```

**The two modes:**

| Mode | Strategy | Best for | Compile time |
|------|----------|----------|-------------|
| `reduce-overhead` | Eliminate Python dispatch overhead | Low-latency serving | 30-60 seconds |
| `max-autotune` | Benchmark many kernel configs, pick fastest | High-throughput batch processing | Several minutes |

**`fullgraph=True`** — tells the compiler the entire model can be compiled as one graph. Faster result, but fails if the model has Python control flow that depends on tensor values. Standard HuggingFace models are safe.

**Key trade-off:**
```
Without compile:   server starts in 30s, each request takes 3s
With compile:      server starts in 90s (30s extra), each request takes 2s

Break-even: 30s extra startup / 1s savings per request = 30 requests
After 30 requests, compile pays for itself
```

**Only compile the UNet** — it runs N times per inference (N = num_inference_steps). Text encoder and VAE each run once — compiling them isn't worth the startup cost.

---

## 3. Attention Slicing

**What:** The UNet's attention layers compute attention across all spatial positions simultaneously. For large images, this attention matrix is huge.

```
Without slicing (512×512):
Attention matrix = seq_len × seq_len
For 512×512 image → latent 64×64 → seq_len = 4096
Matrix = 4096 × 4096 = 16M elements → ~128 MB peak VRAM for this op

With slicing:
Process in chunks of 512 at a time
Peak VRAM for attention: much smaller, but more passes
```

```python
pipe.enable_attention_slicing()  # deprecated shorthand
pipe.unet.set_attention_slice("auto")  # preferred in newer diffusers
```

**Trade-off table:**

| Image size | Use slicing? | Reason |
|------------|-------------|--------|
| 512×512 | Optional | Fits in VRAM easily, slicing adds overhead |
| 768×768 | Yes | Attention matrix starts getting large |
| 1024×1024 | Yes | Nearly required on <8GB VRAM |

**When to disable:** When VRAM is plentiful and you need maximum speed. Slicing adds overhead from multiple partial passes.

---

## 4. VAE Tiling

**What:** Instead of decoding the entire latent to pixels at once, decode in spatial tiles and stitch them together.

```
Without tiling (512×512 → easy):
VAE decodes entire 64×64 latent in one pass ✓

Without tiling (2048×2048 → problem):
VAE tries to decode 256×256 latent in one pass
Peak VRAM for decode: several GB → OOM ✗

With tiling (2048×2048):
Split into 4 tiles of 128×128 latents
Decode each tile independently ✓
Stitch results back together ✓
```

```python
pipe.vae.enable_tiling()   # correct API in diffusers 0.38+
```

**Trade-off:**
- At normal resolutions (512×512, 768×768): adds tile-stitching overhead, no benefit
- At high resolutions (1024×1024+): required to avoid OOM

**When to disable:** For any output below ~1024×1024. Tile boundaries can also introduce subtle artifacts.

---

## 5. `torch.no_grad()` (Always Required)

Not optional — this is required for all inference.

```python
# During training:
output = model(input)
loss = criterion(output, target)
loss.backward()   # needs the computation graph

# During inference:
with torch.no_grad():   # tells PyTorch: don't build the graph
    output = model(input)
    # No .backward() ever called — graph would be wasted VRAM
```

**Impact:**
- VRAM: saves memory proportional to model depth × batch size
- Speed: slight speedup (no graph tracking overhead)
- Required: yes, always use for inference

For a 20-step diffusion model, `no_grad()` eliminates the graph for all 20 UNet passes — significant VRAM savings.

---

## Summary Table — When to Use Each Optimization

| Optimization | VRAM Impact | Speed Impact | Startup Cost | Use When |
|-------------|-------------|--------------|-------------|----------|
| fp16 | -50% | +30-50% | None | Always (CUDA only) |
| `no_grad()` | -10-20% | Slight | None | Always |
| `torch.compile` | None | +20-40% | +30-90s | Production, CUDA only |
| Attention slicing | -20-40% | -10-20% | None | VRAM < 8GB or large images |
| VAE tiling | Large res only | Slight overhead | None | Images > 1024×1024 |
| INT8 quantization | -50-75% | Slight loss | None | VRAM < 4GB |

---

## Interview Answer: "How would you optimize this inference server?"

> "I'd apply optimizations in order of ROI. First, fp16 — halves VRAM and speeds up Tensor Core ops with no code complexity cost. Second, `torch.compile` with `reduce-overhead` mode — fuses ops and reduces Python dispatch overhead at the cost of a 30-60 second startup penalty, worth it after ~30 requests. Third, attention slicing if we're serving high-resolution images or running on limited VRAM. Fourth, VAE tiling only for outputs above 1024×1024. I'd always use `torch.no_grad()` — that's not optional, it's required. If VRAM was extremely constrained, I'd look at INT8 quantization, but that introduces quality degradation so I'd benchmark first. I'd monitor the trade-offs with `nvidia-smi -l 1` during load testing and track P50/P95/P99 latency to see where the gains actually land."
