# Sciforium Inference Engineer — Interview Prep Session
**Date:** 2026-05-28 | **Interview:** Tomorrow (system design discussion)

---

## How this works
- Read each section carefully
- Write your answers in the `### Your Response` blocks
- Tell Claude "read the session file" when ready — Claude will read this file, grade your answer, and continue to the next block

---

---

# BLOCK 2 — Multimodal Serving Architecture

## 2.1 What is a Vision-Language Model?

The core problem: LLMs speak token sequences. Images are not tokens.
The solution: **convert the image into tokens the LLM already understands.**

Two dominant approaches:

**Approach A — Cross-Attention (Flamingo style)**
LLM layers attend to image features via dedicated cross-attention layers.
Image and text stay in separate streams throughout.

**Approach B — Token Projection (LLaVA style)**
Image → Vision Encoder → Projection MLP → visual tokens injected into text token sequence.
Simpler. Currently dominant in production (LLaVA, Qwen-VL, InternVL, LLaVA-1.5).

We focus on **Approach B** — it's what most production systems use.

---

## 2.2 Architecture (LLaVA-style)

```
Image (224×224 px)
        │
        ▼
 Vision Encoder (ViT-L/14)
        │  outputs N patch embeddings of dim D_vision
        ▼
 Projection MLP (or cross-attn)
        │  maps D_vision → D_llm
        ▼
 Visual Tokens [v1, v2, ..., vN]
        │
        ├──────────────────────────────┐
        │                              │
 Visual Tokens [v1..vN]         Text Tokens [t1..tM]
        │                              │
        └──────────┬───────────────────┘
                   │
                   ▼
            LLM Decoder
       (autoregressive generation)
                   │
                   ▼
            Output tokens
```

**Key numbers:**
| Config | Image Size | Patch Embeddings |
|--------|-----------|-----------------|
| ViT-L/14 (standard) | 224×224 | 256 patches |
| ViT-L/14 (LLaVA-1.5) | 336×336 | 576 patches |
| High-res tiling (LLaVA-HD) | 672×672 | 4×576 = 2304 patches |

→ One image ≈ **576 extra tokens** prepended to the LLM's context.

---

## 2.3 How Multimodal Serving Differs from Text-Only

### The prefill problem

Text-only prefill: 50–200 tokens typically.
Multimodal prefill: 576 (image) + text prompt = **700–1000 tokens per image.**
4 images in one request = **2300+ token prefill.**

### The extra latency step

In text-only: request arrives → LLM prefill → decode → done.

In multimodal:
```
request arrives
      │
      ▼
ViT forward pass (20–50ms, compute-bound)   ← NEW STEP
      │
      ▼
Projection (fast, ~1ms)
      │
      ▼
LLM prefill (longer than text-only)
      │
      ▼
Decode → stream tokens
```

TTFT is now dominated by **two serial compute steps** before the first output token.

---

## 2.4 The Four Serving Challenges

| Challenge | Root Cause | Production Fix |
|-----------|-----------|----------------|
| Long prefill stalls other requests | 576+ tokens block GPU | **Chunked Prefill** |
| Image encoding adds to TTFT | ViT is a serial dependency | **Image embedding cache** |
| Batch composition is hard | Variable image sizes (high-res tiling) | **Dynamic resolution bucketing** |
| Memory pressure | ViT weights + LLM weights + KV cache | **MI300X 192GB HBM** |

---

## 2.5 Chunked Prefill — the key fix

**Problem:** A 700-token multimodal prefill runs for ~7ms on a single GPU. Every decode step for *other* requests in the batch is stalled during this time.

**Solution:** Split the prefill into fixed-size chunks (e.g., 256 tokens). Between chunks, let ongoing decode sequences take a step.

```
Naive (full prefill, then resume decode):
  [===== prefill 700 tok =====][decode B][decode B][decode B]

Chunked prefill (256-token chunks):
  [prefill 256][decode B][prefill 256][decode B][prefill 188][decode B]
```

Effect: TTFT for the new request is slightly higher (a few ms). P99 tail latency for existing requests improves dramatically. GPU utilization stays high.

→ vLLM 0.4+ implements this. SGLang uses it too.

---

## 2.6 Image Embedding Cache

**Problem:** If the same image is queried multiple times (product image, shared document), you're re-running ViT every time — wasting 20–50ms per request.

**Solution:** Cache the *projected visual tokens*, keyed by image hash.

```
Cache key:   SHA256(image bytes)
Cache value: [576 × D_llm] tensor of projected visual tokens
TTL:         1 hour (invalidate if model weights update)
Storage:     GPU memory (hot) → CPU memory (warm) → Redis (cold)
```

Cache hit → skip ViT + projection entirely → TTFT drops by 20–50ms.

---

---

# CHECK-IN QUESTION — Block 2

> **Q1:** A user sends a request with **3 images** and a **50-token text prompt**.
>
> (a) What is the approximate prefill length the LLM sees?
>
> (b) Name **two components** that contribute to TTFT in this request that do NOT exist in a text-only LLM serving system.
>
> (c) If this same 3-image combination is queried by 10,000 users per hour, what optimization would you apply and what does it save?

### Your Response
<!-- Write your answer below this line -->

1 image = 576 extra tokens
total number of tokens = 3 * 576 + 50 = 1778 tokens

We would have to convert the images into tokens. Tho I do not understand the clear answer for it. 
Based on above doc, ViT + Project (MLP , cross attention) converts images into visual tokens

if the same 3 -page combination is used frequently, cache them into GPU HMM if possible (fallback to CPU cache, then fallback to Redis)


---

> **Q2:** You're designing the scheduler for a multimodal serving system.
> A new request arrives with 2 images. There are 8 ongoing decode sequences in the batch.
>
> Walk me through what happens step by step — from request arrival to first output token.
> Be specific about which components run and in what order.

### Your Response
<!-- Write your answer below this line -->

2 images means 2 * 576 tokens = 1152 tokens
scheduler needs to be able to do chunk prefill to avoid decode sequences a long wait time

scheduler is simly a queue abstraction.
Lets say each image request (Prefill, Image, size) and other requests (Decode, xxx, xxxx)

Queue : (Prefill, Image, size), (Decode, xxx, xxxx), ...

Scheduler picks up the first request of the queue

if req is of type prefill, we know the size has to 576 tokens so if size < 576 , we prefill a chunk 256 and add it back to thw queue (Prefill, Image , size + 256) else if size >= 576 tokens, move it to Decode and add it back to the queue.
if req is of type Decode, decode it :)
---

---

---

# FEEDBACK — Block 2 Check-in

## Q1 Feedback

**(a) ✓ Correct.** 3×576 + 50 = 1778 tokens. Exact.

**(b) Partial — here's the full answer:**
The question asks specifically what ADDS to TTFT that doesn't exist in text-only serving.
- ✓ You named the right components (ViT + Projection)
- The sharper answer: **(1) ViT forward pass** (~20–50ms of pure GPU compute before the LLM even starts) and **(2) the extra prefill length** (1728 more tokens than a text-only request, ~10–15ms extra).
- Projection itself is ~1ms — negligible. Mention ViT and prefill length as the two costs.

**(c) ✓ Correct concept.**
- Cache the projected visual tokens (not the raw image) tiered as: GPU HBM → CPU RAM → Redis
- One correction: "GPU HMM" → the correct term is **HBM (High Bandwidth Memory)** — that's the physical memory type on GPU dies (AMD MI300X has 192GB HBM3e, H100 has 80GB HBM3)

---

## Q2 Feedback

**Good instinct** — you correctly identified chunked prefill as the key tool.

**Critical gap:** You modeled the scheduler as managing the ViT step. It doesn't. The LLM scheduler only manages LLM compute. The ViT runs in a **completely separate stage** before the scheduler sees the request. This is a common misconception — important to have right.

Here is the correct step-by-step flow:

```
Step 1 — GATEWAY
  Request arrives: 2 images + text
  Gateway dispatches images to Vision Encoder Pool (separate workers)

Step 2 — VISION ENCODER POOL (runs in parallel with waiting for GPU slot)
  ViT forward pass on image 1 → 576 patch embeddings
  ViT forward pass on image 2 → 576 patch embeddings
  Projection MLP: 576×D_vision → 576×D_llm  (×2)
  Duration: ~40–100ms (two ViT passes, batched if possible)
  Output: two tensors of shape [576, D_llm]

Step 3 — SCHEDULER ADMISSION
  Vision encoding is done. Scheduler now sees:
    - visual_tokens: [1152, D_llm]  (2 images × 576)
    - text_tokens: [text_token_ids]
    - total prefill length: 1152 + len(text)
  KV cache pages allocated for this sequence

Step 4 — CHUNKED PREFILL (interleaved with 8 decode sequences)
  GPU step 1: Prefill tokens 0–255      → 8 decode sequences take 1 step each
  GPU step 2: Prefill tokens 256–511    → 8 decode sequences take 1 step each
  GPU step 3: Prefill tokens 512–767    → 8 decode sequences take 1 step each
  GPU step 4: Prefill tokens 768–1023   → 8 decode sequences take 1 step each
  GPU step 5: Prefill tokens 1024–1151+text → prefill complete
  KV cache now fully populated for this sequence

Step 5 — FIRST TOKEN (TTFT crossed)
  New request joins the decode batch (now 9 sequences)
  LLM generates token 1 → streamed to client
  TTFT = ViT time + chunked prefill time
```

**Key design insight:** The Vision Encoder Pool and LLM GPU are **different resources**.
A smart system starts ViT encoding while waiting for a GPU slot in the LLM scheduler —
so ViT latency can be hidden (overlapped) rather than always serial.

---

---

# BLOCK 1.5 — Foundations (Tokens, Prefill, Decode, ViT, MLP)

*Read this before continuing — these are the building blocks everything else rests on.*

---

## 1.5.1 What is a Token?

An LLM does not read characters or words — it reads **integer IDs** called tokens.

A **tokenizer** (e.g., BPE — Byte Pair Encoding) converts raw text into token IDs:

```
Text:   "Hello, world!"
Tokens: [9906, 11, 1917, 0]   ← integer IDs

Text:   "transformer"
Tokens: [47891]                ← one token (common word)

Text:   "supercalifragilistic"
Tokens: [2169, 1310, 2352, 454, 4192]  ← rare word → multiple tokens
```

Each token ID maps to an **embedding vector** (e.g., 4096-dimensional float).
The LLM operates entirely on these embedding vectors — not on raw text.

**Rule of thumb:** 1 token ≈ 0.75 English words. "Hello world" ≈ 2–3 tokens.

---

## 1.5.2 What is Prefill?

When a request arrives, the user sends a **prompt** (e.g., "Summarize this article: ...").

**Prefill** = the LLM processes the entire prompt in **one forward pass**.

```
Prompt: [t1, t2, t3, t4, t5]  ← 5 tokens

Prefill: LLM processes all 5 tokens IN PARALLEL (like a regular neural net)
         Output: KV cache populated for all 5 positions
         Duration: proportional to prompt length, fast because parallel
```

Why is it called prefill? Because it **fills** the **KV cache** (Key-Value cache) —
the memory structure that stores computed attention state for all prompt tokens.

After prefill, the model is ready to generate output.

---

## 1.5.3 What is Decode?

After prefill, the LLM generates output one token at a time. This is **decode**.

```
After prefill of [t1..t5]:

Step 1: LLM sees [t1,t2,t3,t4,t5] → predicts t6  (KV cache: positions 1-5)
Step 2: LLM sees t6 only (rest in KV cache) → predicts t7  (KV cache: 1-6)
Step 3: LLM sees t7 only → predicts t8  (KV cache: 1-7)
...repeat until <EOS> token
```

Each decode step is **autoregressive** — each new token depends on all previous tokens.
The **KV cache** avoids recomputing attention for old tokens on every step.

**Why decode is slow:** Each step generates exactly ONE token. You can't parallelize it.
A 200-token response = 200 sequential LLM forward passes.

---

## 1.5.4 Prefill vs Decode: The Fundamental Asymmetry

| Property | Prefill | Decode |
|----------|---------|--------|
| Parallelism | All tokens processed at once | One token per step |
| Bound by | Compute (FLOPS) | Memory bandwidth (reading weights) |
| Duration | Fast (one pass, regardless of length*) | Slow (N passes for N tokens) |
| KV cache | Written | Read |
| GPU utilization | High | Low (memory-bound, not compute-bound) |

*Prefill does get longer with longer prompts, but it's still one pass.

This asymmetry is why LLM serving is hard to optimize — the two phases have opposite bottlenecks.

---

## 1.5.5 What is a ViT (Vision Transformer)?

A **Vision Transformer** applies the transformer architecture to images.

**Step 1 — Patchify:**
Split the image into fixed-size patches (e.g., 14×14 pixels each).
A 224×224 image → (224/14)² = 256 patches.

```
224×224 image
┌──────────────────┐
│ p0  p1  p2  p3  │   Each cell = 14×14 pixel patch
│ p4  p5  p6  p7  │   256 patches total for 224×224
│ ...             │
└──────────────────┘
```

**Step 2 — Linear projection:**
Each patch (14×14×3 = 588 pixel values) → projected to embedding dim D (e.g., 1024).
Now you have 256 vectors of size 1024. These are "patch tokens."

**Step 3 — Transformer encoder:**
Run the 256 patch tokens through a standard transformer (multi-head attention + MLP blocks).
Output: 256 contextualized patch embeddings of size 1024.

**Key insight:** ViT treats image patches exactly like text tokens. The same transformer
math applies — patches can attend to each other to capture spatial relationships.

---

## 1.5.6 What is the Projection MLP (in VLM context)?

The ViT outputs embeddings of size D_vision (e.g., 1024 for ViT-L).
The LLM expects input embeddings of size D_llm (e.g., 4096 for LLaMA-3-8B).

The **Projection MLP** is a small 2-layer feedforward network that bridges this gap:

```
Input:  [256 or 576 vectors of size 1024]   ← ViT output
            │
         Linear(1024 → 4096)
         GELU activation
         Linear(4096 → 4096)
            │
Output: [256 or 576 vectors of size 4096]   ← Now LLM-compatible
```

These projected vectors are dropped into the token sequence, and the LLM treats them
as if they were regular text token embeddings. The LLM never "knows" they came from an image.

---

---

# BLOCK 4 — AMD / ROCm Stack

*Sciforium is AMD-sponsored. Expect at least one question here.*

---

## 4.1 Why AMD Matters for Inference

Sciforium specifically chose AMD hardware. Here's why an inference engineer should care:

**AMD MI300X specs (key inference GPU):**
```
HBM3e memory:     192 GB   ← NVIDIA H100 has 80 GB
Memory bandwidth: 5.3 TB/s ← H100 has 3.35 TB/s
FLOPS (FP16):     1307 TFLOPS ← H100 has 989 TFLOPS
Form factor:      Single GPU
```

**The 192GB advantage:**
- LLaMA-3 70B in FP16 = ~140 GB → fits on ONE MI300X
- On H100 you'd need 2 GPUs → tensor parallelism required → inter-GPU communication overhead
- On MI300X: single GPU, no communication cost, simpler serving stack

**Implication for system design:**
> On AMD MI300X, your default inference strategy for a 70B model is **no model parallelism**.
> You serve the whole model from one GPU. You scale by adding more GPUs (replicas), not by splitting the model.

---

## 4.2 ROCm vs CUDA — What's Different?

ROCm is AMD's equivalent of CUDA. The programming model is nearly identical.

| CUDA (NVIDIA) | ROCm (AMD) | Notes |
|---------------|-----------|-------|
| CUDA kernels (.cu) | HIP kernels (.hip or .cu) | HIP syntax ≈ CUDA syntax |
| cudaMalloc | hipMalloc | 1:1 mapping |
| cudaLaunchKernel | hipLaunchKernelGGL | near-identical |
| cuBLAS | rocBLAS / hipBLAS | same API surface |
| cuDNN | MIOpen | near-equivalent |
| Nsight profiler | ROCm Profiler (rocprof) | different tools, same concepts |
| NVLink (GPU interconnect) | Infinity Fabric | AMD's interconnect, lower bandwidth |

**hipify:** AMD provides a tool that auto-translates CUDA code to HIP.
~90% of CUDA code translates automatically. The remaining 10% needs manual porting.

---

## 4.3 JAX on AMD

Sciforium uses JAX. JAX uses **XLA** as its compiler backend.

```
JAX Python code
      │
      ▼
   XLA IR (HLO)
      │
      ├─── NVIDIA: nvptx backend → CUDA kernels
      └─── AMD: ROCm backend → HIP kernels
```

The same JAX code runs on AMD GPUs — XLA handles the backend difference.

**JAX model parallelism on AMD:**
- `jax.pmap` / `jax.device_put_sharded` → distributes arrays across GPUs
- Internally uses RCCL (AMD's equivalent of NCCL) for collective operations
- Key difference: AMD GPUs communicate via PCIe (no NVLink) across nodes
  → Prefer **pipeline parallelism** (pass activations sequentially) over tensor parallelism
  (requires AllReduce every layer = lots of inter-GPU communication)

---

## 4.4 Parallelism Strategy — AMD vs NVIDIA

This is a system design question trap. Know the reasoning:

```
NVIDIA H100 cluster:
  - NVLink: 900 GB/s intra-node bandwidth
  - AllReduce across 8 GPUs is fast
  → Tensor Parallelism works well (TP=8 for a node)
  → Typical: TP=8 within node, PP across nodes

AMD MI300X cluster:
  - Infinity Fabric: up to 896 GB/s (within APU package, lower across chips)
  - Standard PCIe between separate MI300X cards: ~64 GB/s
  → AllReduce is expensive
  → Prefer Pipeline Parallelism (fewer AllReduces)
  → Or: fit the whole model on ONE MI300X (192GB!) and just replicate
```

**In a system design interview:** If someone asks "how would you serve a 70B model on AMD MI300X?" — the right answer is *one GPU, no parallelism needed*, and scale with replicas.

---

---

# CHECK-IN QUESTION — Block 4

> **Q3:** You're joining Sciforium as an Inference Engineer. Your manager says:
> "We're porting our LLaMA-3 70B serving stack from H100 to MI300X.
>  The H100 version uses TP=4 (tensor parallelism across 4 GPUs per replica)."
>
> (a) On MI300X, would you still use TP=4? Why or why not?
>
> (b) What changes about memory management when moving from H100 to MI300X?
>
> (c) The H100 stack uses cuBLAS and Nsight for profiling. What are the AMD equivalents?

### Your Response
<!-- Write your answer below this line -->

a. Nope you dont 
b. No need for tensor parallelism 
c. hipBLAS & ROCm profiler


---

> **Q4:** In a multimodal serving system on MI300X, a request arrives:
> - 4 images (high-res, 672×672 px each — LLaVA-HD tiling)
> - 100-token text prompt
>
> (a) How many visual tokens does the LLM see from these 4 images?
>
> (b) What is the total prefill length?
>
> (c) Name ONE advantage MI300X gives you for this specific request over an H100 setup.

### Your Response
<!-- Write your answer below this line -->

a. 4 * 576 = 2304 tokens
b. 2304 tokens
c. 192 GM HBM memory


---

---

---

# FEEDBACK — Block 4 Check-in

## Q3 Feedback

**(a) ✓ Right answer, needs stronger justification.**
"Nope" is correct — but interviewers always follow up with "why not?"
Full answer to give: *"MI300X has 192GB HBM. LLaMA-3 70B in FP16 is ~140GB — it fits on ONE GPU.
On H100 (80GB each), you need at least 2 GPUs just to hold the weights, hence TP=4.
On MI300X, TP=1 (single GPU). You scale by running more replicas, not splitting one model."*

(a-followup) How do u compute the eize of model ? Also if the model occupies most of space on HBM. What happens when a model is loaded on GPU, how it is loaded into SRAM or DRAM , we can try go into a bit of internals

**(b) Missed — you restated (a).**
"Memory management" = how GPU memory is organized and who owns what.

H100 with TP=4:
- Each GPU holds 1/4 of each weight matrix
- AllReduce buffers allocated for each layer's forward pass (~GB of scratch memory)
- KV cache sharded: each GPU stores KV for a subset of attention heads
- Requires careful coordination to avoid OOM across 4 devices

MI300X with TP=1:
- One GPU holds ALL weights (simpler memory layout)
- No AllReduce buffers needed
- KV cache is local, no cross-GPU coordination
- Larger KV cache available (192GB - 140GB weights = ~50GB free for KV)

(b - followup) How is KV cache hosted ? What does it mean for KB cache to be local - will it b present in SRAM or HBM ? if HBM, would that not be a lot of I/O 

**(c) ✓ Perfect.** hipBLAS + ROCm Profiler. Exactly right — clean answer.

---

## Q4 Feedback

**(a) Wrong — the "LLaVA-HD tiling" hint was the trap.**
672×672 px is NOT the same as 336×336. The table in your notes says:
- High-res tiling at 672×672 = **4 sub-images × 576 patches = 2304 patches per image**
- So 4 images = 4 × 2304 = **9216 visual tokens**
- Not 4 × 576 = 2304. That's the standard 336×336 answer.

High-res tiling means: split the big image into 4 tiles of 336×336, encode each tile separately with ViT, concatenate outputs. You get 4× the tokens but 4× the spatial detail.

(a - followup) why is 336 x 336 the standard ? 

**(b) Wrong (follows from a).** Correct answer: 9216 + 100 = **9316 tokens total**.

(b- followup) how did we obtain the 100 tokens from ? 

**(c) Correct direction — sharpen it.**
Good enough answer: "192GB HBM means more memory."
Better answer: *"9316-token prefill generates a massive KV cache. On H100 (80GB), model weights
(~140GB) don't even fit on one GPU, so you'd be sharding KV across multiple GPUs with communication
overhead. On MI300X, the entire model + KV cache for this request lives on one device — no cross-GPU
KV coordination, lower latency, simpler scheduler."*

(c-followup) What does pre-fill mean in this context ? Do we load and keep them readu in HBM for model inference purposes ? 
ALso is communication overhead such a big problem ? -- NVLink seems super fast and high thruput ? 

---

---

# BLOCK 4.5 — JAX and XLA Explained

*You asked "what is JAX and XLA?" — here it is from first principles.*

---

## What is JAX?

JAX is a Python library from Google for high-performance numerical computation.

Think of it as **NumPy, but designed for accelerators (GPU/TPU) and with a compiler underneath.**

Four core capabilities:

```
1. grad   — automatic differentiation (compute gradients of any function)
2. jit    — Just-In-Time compilation (compile Python functions to fast kernels)
3. vmap   — vectorization (run a function over a batch automatically)
4. pmap   — parallelization (run a function across multiple devices)
```

**NumPy vs JAX comparison:**
```python
# NumPy — runs on CPU, no compilation
import numpy as np
y = np.dot(A, x)

# JAX — runs on GPU, compiled by XLA
import jax.numpy as jnp
from jax import jit

@jit                    ← this triggers XLA compilation
def matmul(A, x):
    return jnp.dot(A, x)

y = matmul(A, x)        ← first call: compiles. subsequent calls: cached kernel, fast
```

**Why inference engineers care about JAX:**
- `@jit` fuses multiple operations into one GPU kernel — fewer memory round-trips → faster
- `pmap` makes multi-GPU parallelism declarative — no manual NCCL calls
- `shard_map` (newer) handles complex model sharding patterns used in large model serving

---

## What is XLA?

XLA stands for **Accelerated Linear Algebra**. It's the compiler that sits under JAX.

When you write JAX code, you're writing high-level math. XLA's job is to translate that math into optimized machine code for a specific hardware target.

**The compilation pipeline:**

```
JAX Python code
      │
      │  JAX traces the function, builds a computation graph
      ▼
  Jaxpr (JAX's intermediate representation)
      │
      │  Converted to XLA's IR
      ▼
  HLO (High-Level Operations) — hardware-independent IR
      │
      │  XLA optimizer runs:
      │    - Operator fusion (combine ops to reduce memory I/O)
      │    - Layout optimization (choose best memory layout per op)
      │    - Constant folding (pre-compute static values)
      │    - Loop unrolling, vectorization
      ▼
  Backend-specific code generation:
      ├── NVIDIA GPU → PTX → CUDA kernels
      ├── AMD GPU   → ROCm backend → HIP kernels
      └── TPU       → TPU native instructions
```

**Operator fusion — why it matters:**

Without fusion, each operation reads from and writes to GPU memory separately:
```
# 3 operations = 3 memory round-trips
x = A @ B          # matmul: read A,B → write x to HBM
y = x + bias       # add:    read x,bias → write y to HBM
z = relu(y)        # relu:   read y → write z to HBM
```

With XLA fusion, all three ops become ONE kernel:
```
# Fused: read A,B,bias once → write z once
# Intermediate values (x, y) stay in fast register/SRAM — never touch HBM
z = fused_matmul_bias_relu(A, B, bias)
```

For a transformer with dozens of operations per layer, fusion cuts HBM bandwidth usage dramatically.

Does that mean results of each GPU compute are stored on HBM instead of SRAM ? 
To draw a parallel to CPU, is it like storing in memory instead of L1 cache ? 

---

## JAX vs PyTorch — Why Sciforium Might Choose JAX

| Property | PyTorch | JAX |
|----------|---------|-----|
| Compilation | `torch.compile` (newer) | `@jit` (native, mature) |
| Parallelism | FSDP / DDP (complex) | `pmap` / `shard_map` (functional) |
| Hardware | CUDA-first, ROCm via backend | Backend-agnostic via XLA |
| Style | Imperative (eager) | Functional (pure functions) |
| Debugging | Easy (print tensors anytime) | Harder (traced, not executed) |
| Research use | Very common | Common at Google/DeepMind |

Sciforium is AMD-backed and building a **custom serving platform** — JAX + XLA makes sense because:
1. XLA's backend portability: same code compiles for NVIDIA OR AMD
2. Operator fusion via XLA reduces memory bandwidth pressure (critical on any hardware)
3. `shard_map` gives fine-grained control over model partitioning for large multimodal models

Why is PyTorch CUDA first ? 

---

---

# BLOCK 5 — Full System Design Walkthrough

*This is what you'll actually say in the interview. Practice narrating this out loud.*

---

## The Question

> "Design a serving platform for a multimodal LLM at scale.
> Assume a 70B parameter model, image + text inputs, and a target of 10K requests/second."

---

## How to Structure Your Answer (5-part framework)

### Part 1 — Clarify Requirements (2 min)

Always ask first. Suggested questions:
- What's the latency SLA? (TTFT target? TPOT target?)
- What's the image input profile? (max images per request? typical resolution?)
- Is this online (streaming) or offline (batch) serving?
- What hardware is available? (This is where you'd mention MI300X if Sciforium)
- Are there expected hot images (same image queried repeatedly)?

Assumed answers to unblock the design:
- TTFT < 2s, TPOT < 50ms
- Up to 4 images per request, 336×336 px typical (LLaVA-1.5 resolution)
- Online serving with streaming output
- AMD MI300X GPUs
- Yes, some images are shared (product catalog, documents)

---

### Part 2 — High-Level Architecture

```
                     ┌─────────────────────────────────────────┐
                     │           API Gateway / LB               │
                     │  - Route by request type                 │
                     │  - Rate limiting, auth                   │
                     └──────────┬──────────────────────────────┘
                                │
               ┌────────────────┴─────────────────┐
               │                                  │
               ▼                                  ▼
  ┌────────────────────────┐        ┌─────────────────────────────┐
  │   Vision Encoder Pool  │        │      Request Queue          │
  │                        │        │  (waits for vision results) │
  │  - ViT forward passes  │        └──────────────┬──────────────┘
  │  - Projection MLP      │                       │
  │  - Image embed cache   │                       │ (visual tokens ready)
  │    (SHA256 → tensors)  │                       │
  └──────────┬─────────────┘                       │
             │ projected visual tokens              │
             └──────────────────────────────────────┘
                                │
                                ▼
              ┌─────────────────────────────────────┐
              │         LLM Serving Fleet            │
              │                                      │
              │  Each replica = 1× MI300X (192GB)   │
              │  - Full 70B model, no TP needed      │
              │  - PagedAttention KV cache           │
              │  - Continuous batching               │
              │  - Chunked prefill (256-tok chunks)  │
              └──────────────────┬───────────────────┘
                                 │
                                 ▼
                    ┌────────────────────────┐
                    │  Token Stream Router   │
                    │  - SSE / WebSocket     │
                    │  - Back to client      │
                    └────────────────────────┘
```

---

### Part 3 — Key Design Decisions (where you show depth)

**Decision 1: Vision Encoder Pool is separate from LLM Fleet**
- ViT runs on separate GPU workers (or CPU for small ViT models)
- Decoupled scaling: if image traffic spikes, add ViT workers, not LLM replicas
- ViT encoding starts immediately on request arrival, before waiting for an LLM slot
- Result: ViT latency is overlapped with queue wait time → lower effective TTFT

**Decision 2: Image Embedding Cache**
- Key: SHA256(image bytes) → [576 × D_llm] tensor
- Tier 1: In-process GPU HBM buffer (microseconds, limited to ~1000 entries)
- Tier 2: CPU RAM (milliseconds, ~100K entries)
- Tier 3: Redis cluster (tens of ms, millions of entries)
- For a product catalog or shared documents: cache hit rate can be >80%

**Decision 3: Chunked Prefill in the LLM Scheduler**
- Chunk size: 256 tokens
- A 4-image request (2304 visual + 100 text = 2404 token prefill) → 10 chunks
- Each chunk interleaved with decode steps from the ongoing batch
- Trade-off: TTFT increases by ~10ms (10 decode interruptions), P99 for other users improves dramatically

**Decision 4: One MI300X per replica (no TP)**
- 70B in FP16 = ~140GB → fits in 192GB HBM with ~50GB left for KV cache
- ~50GB KV headroom supports ~35 simultaneous 4K-token sequences (estimate)
- Scale horizontally: more replicas, not more GPUs per replica
  - Dont we need more GPUs as HBM are tied to GPU ? 
- Simplicity win: no AllReduce, no distributed KV management

---

### Part 4 — Metrics to Track

| Metric | What it measures | Target |
|--------|-----------------|--------|
| TTFT | Time from request to first token | < 2s |
| TPOT | Time per output token | < 50ms |
| Vision encode p99 | ViT forward pass tail latency | < 100ms |
| Image cache hit rate | ViT work saved | > 60% if hot images |
| KV cache utilization | % of HBM used for KV | 60-80% (higher = better) |
| GPU MFU | Model FLOP Utilization | > 40% |
| Requests queued | Backpressure signal | < 100 |

---

### Part 5 — Scaling and Failure Modes (show you think in production)

**Scaling:**
- Horizontal: add MI300X replicas behind the load balancer
- Vision pool: scale independently from LLM fleet
- Cache: Redis cluster scales separately

**Failure modes to mention:**
- Image embedding cache cold start: first deploy has 0% cache hit rate → spike in ViT load
- KV cache OOM: if requests are too long, the scheduler must preempt (swap KV to CPU) or reject
- Prefill starvation: if too many long prefill requests queue up, TPOT for ongoing sequences degrades → admission control needed

---

---

# FINAL CHECK-IN — Practice Design

> **Q5 (open-ended, no wrong answer — practice articulating):**
>
> You're presenting this design to Sciforium's team.
> An engineer asks: *"Why did you choose to put the Vision Encoder in a separate pool
> rather than co-locating it on the same GPU as the LLM?"*
>
> Give a 3–4 sentence answer.

### Your Response
<!-- Write your answer below this line -->
Vision encoder is needed for prefill of images of request. They r not the core of forward pass of the inference.
So now, Inference pipeline only requires the embeddings so we can decouple them and scale separately ? 
this would add network IO is my main concern. 
Otherwise it is not coupled to the machine runing the actual inference.


---

> **Q6:** Another engineer asks:
> *"What happens to your system if a user sends a 4K×4K resolution image?"*
>
> Walk through the failure mode and how you'd defend against it.

### Your Response
<!-- Write your answer below this line -->
I dont know what 4K x 4K image is. 
but I think it is a very large. 

- Try to fetch from cache (HBM, RAM, Redis)
- If it is present, just use that
- if it is too big to fit in any of cache, can we split the request into 4 parts and run inference 


---

---

---

# FEEDBACK — Final Check-in

## Q5 Feedback — Why Separate Vision Encoder Pool?

**What you got right:**
- ✓ Decouple and scale independently — this is correct and important
- ✓ Network I/O is a real trade-off to mention — shows you think in production

**What was missing:**

**Reason 1 — Latency hiding (most important):**
When the request arrives, the LLM GPU is probably busy (10K req/s means the queue is never empty).
The request will WAIT before getting an LLM slot. If ViT is separate, it runs DURING that wait.
Co-located: wait in queue → get GPU slot → run ViT → run LLM prefill → decode.
Separate pool: ViT runs immediately on arrival → by the time an LLM slot opens, visual tokens are READY.
Net effect: ViT latency (40–100ms) is hidden inside queue wait time → lower effective TTFT.

**Reason 2 — Resource efficiency:**
ViT-L is ~300M params (~600MB). The LLM is 70B params (~140GB).
Sharing one MI300X GPU means the LLM compute units sit idle while ViT runs (and vice versa).
Separate workers: ViT runs on smaller/cheaper GPU or CPU, LLM GPU runs at max utilization.

**Interview answer (3–4 sentences to practice):**
*"The key reason is latency hiding. At 10K req/s the LLM queue is never empty — new requests always
wait before getting a GPU slot. If the Vision Encoder is a separate pool, ViT encoding starts the
moment the request arrives and runs in parallel with the queue wait. By the time an LLM slot opens,
the visual tokens are already ready — so ViT's 40–100ms cost is absorbed rather than added to TTFT.
The secondary benefit is independent scaling: an image traffic spike means we add ViT workers,
not expensive LLM replicas."*

---

## Q6 Feedback — What Happens with a 4K×4K Image?

**First: what IS a 4K×4K image?**
4K×4K = 4096×4096 pixels. That's a high-resolution photo (16 megapixels).
Compare: LLaVA-1.5 standard input is 336×336. LLaVA-HD handles up to 672×672.

**The failure mode — token explosion:**

With LLaVA-HD tiling, the system tiles large images into 336×336 sub-images:
```
4096×4096 image
→ tiles needed: ceil(4096/336) × ceil(4096/336) ≈ 13×13 = 169 tiles
→ visual tokens: 169 tiles × 576 patches = 97,344 tokens from ONE image
```

Add 100 text tokens → **97,444 token prefill for a single request.**

This would:
1. Exhaust the KV cache immediately (50GB headroom ≈ 35 normal sequences, but this ONE request needs ~10× that)
2. Stall the entire GPU batch for seconds during chunked prefill (97,444 / 256 = 381 chunks)
3. Effectively be a denial-of-service attack if malicious

**How to defend against it (3-layer approach):**

```
Layer 1 — Gateway validation (before ViT even runs)
  - Reject images above max resolution (e.g., 2048×2048 = 4MP hard cap)
  - Return HTTP 400 with "image too large" error
  - Cost: ~1ms. Saves the entire downstream pipeline.

Layer 2 — Server-side downsampling (for slightly oversized images)
  - Images between 672px and 2048px → downsample to max supported resolution
  - Preserve aspect ratio
  - User gets slightly lower quality response, not an error

Layer 3 — Token budget enforcement in scheduler
  - If visual tokens > budget (e.g., 4096), reject or truncate
  - Defense-in-depth: catches any edge cases that slip past layer 1
```

**Your instinct** — "split into 4 parts" — is actually what LLaVA-HD tiling already does automatically for moderately large images. But for 4K×4K, even tiling explodes. The correct fix is rejection/downsampling BEFORE tiling.

---

## Inline Question — "Don't we need more GPUs as HBM is tied to GPU?"

Yes — excellent catch. You're completely right. Let me clarify what "scale horizontally" means:

```
H100 serving (TP=4):
  1 replica = 4 H100s (model split across 4 GPUs)
  100 replicas = 400 H100s total
  Throughput: 10K req/s

MI300X serving (TP=1):
  1 replica = 1 MI300X (whole model on one GPU)
  100 replicas = 100 MI300Xs total
  Throughput: 10K req/s (same)
```

Same throughput, 4× fewer GPU units. That's the efficiency win.

You still need MANY MI300X GPUs at scale — just fewer of them to hit the same throughput target.
"Scale horizontally" = add replicas (more GPUs), not split one model across more GPUs (model parallelism).

---

---

# INTERVIEW CHEAT SHEET — Read This Tomorrow Morning

*5 concepts to have crisp in your head before the call.*

---

## 1. The Multimodal Serving Pipeline (say this fluently)

```
Request arrives
    → Gateway validates image size
    → Vision Encoder Pool: ViT forward pass (~40ms) + projection (~1ms)
    → LLM Scheduler: admission, KV page allocation
    → Chunked prefill: 256-token chunks, interleaved with ongoing decode
    → Decode: autoregressive, streaming to client
```

Two separate resource pools: Vision Encoder Pool and LLM Fleet. They scale independently.

---

## 2. The Three Numbers for MI300X

- **192 GB HBM3e** — LLaMA-3 70B (~140GB) fits on ONE GPU. No tensor parallelism needed.
- **5.3 TB/s memory bandwidth** — 1.6× faster than H100 at reading weights → faster decode
- **1307 TFLOPS FP16** — 1.3× more compute than H100

---

## 3. Chunked Prefill vs Standard Prefill

Standard: one giant prefill blocks the GPU → other sequences' TPOT spikes.
Chunked: split into 256-token pieces, interleave with decode → TTFT slightly higher, P99 TPOT stable.

For multimodal: 4-image request = ~2400 token prefill = 10 chunks. Without chunking, the batch stalls for ~24ms.

---

## 4. JAX + XLA in One Sentence

JAX is NumPy for GPUs with auto-differentiation. XLA is the compiler underneath it that fuses operations and generates hardware-specific kernels (CUDA for NVIDIA, HIP/ROCm for AMD). Sciforium uses it because the same code compiles for AMD without rewriting anything.

---

## 5. Why Sciforium Builds a Custom Serving Platform

vLLM and TensorRT-LLM are CUDA-first. On AMD hardware with JAX-trained models, you can't just drop in vLLM. You need:
- XLA-compiled kernels instead of CUDA kernels
- ROCm memory management (hipMalloc, rocBLAS)
- Custom scheduler that understands multimodal batching (vision tokens + text tokens)
- AMD-specific parallelism strategy (pipeline > tensor parallelism due to PCIe interconnect)

This is why inference infrastructure companies build in-house rather than wrapping open-source.

---

*Good luck tomorrow. You know this.*
