# Inference Serving Concepts

## Latency vs Throughput

These are the two fundamental metrics in inference serving. They often trade off against each other.

```
LATENCY: Time for ONE request to complete
─────────────────────────────────────────
Request ──►[    inference    ]──► Response
           |←── 2 seconds ──►|

THROUGHPUT: How many requests you can serve per unit time
──────────────────────────────────────────────────────────
          ┌─────────┐
Req 1 ───►│         │──► Resp 1
          │  GPU    │              5 images/minute = throughput
Req 2 ───►│         │──► Resp 2
          └─────────┘
```

| Metric | Definition | Unit | Optimized by |
|--------|-----------|------|--------------|
| **Latency** | Time per single request | ms or seconds | Reduce steps, smaller model, compile |
| **Throughput** | Requests completed per second | req/s or img/s | Batching, parallelism |
| **P50 latency** | Median — 50% of requests are faster | ms | — |
| **P95 latency** | 95% of requests are faster than this | ms | — |
| **P99 latency** | 99% of requests are faster than this | ms | — |

**Why P99 matters more than average:**
If average latency is 2s but P99 is 30s, 1 in 100 users waits 30 seconds. In production you optimize for P99, not average.

---

## The Latency-Throughput Tradeoff

```
Individual requests (low throughput, low latency):

Time:  0s    2s    4s    6s    8s
       [Req1] [Req2] [Req3] [Req4]

Latency: 2s each. Throughput: 0.5 req/s

Batched requests (high throughput, higher latency):

Time:  0s         3s         6s
       [Req1+2+3] [Req4+5+6]

Latency: 3s each (slightly slower). Throughput: 1 req/s (2× better)
```

For user-facing APIs (like Luma's): **latency is king**. Users don't want to wait.
For offline processing (batch jobs): **throughput is king**.

---

## Batching

Running multiple inputs through the model in a single forward pass.

```python
# Without batching (sequential):
result1 = pipe("a cat")      # 2s
result2 = pipe("a dog")      # 2s
# Total: 4 seconds, 2 images

# With batching:
results = pipe(["a cat", "a dog"])   # ~2.5s
# Total: 2.5 seconds, 2 images
```

**Why batching is faster:** The GPU has thousands of cores. One prompt uses only a fraction of them. Two prompts together use more cores in parallel — same time, double the output.

**Limits:**
- Each extra item in a batch uses proportionally more VRAM
- With 6GB VRAM, you can only batch so many images before OOM
- For SD at 512×512 fp16: roughly 2-4 images per batch

In your server, `max_workers=1` means no batching — one request at a time. This is fine for a demo/interview but a real production system would implement a request queue + dynamic batching.

---

## Async, Event Loop, and Thread Pool

This is the most important serving concept to understand.

### The Event Loop

FastAPI is built on asyncio — a **single-threaded** event loop that handles many connections concurrently by switching between them when one is waiting.

```
Event Loop (single thread):

Time ──►
[Handle Req1 header] [Switch] [Handle Req2 header] [Switch] [Send Resp1]
                                                    (Req1 was waiting for DB)
```

This works great for I/O-bound work (database queries, HTTP calls) because the thread can switch while waiting.

**Problem:** GPU inference is NOT I/O. It's CPU-bound Python code that blocks.

```
WITHOUT ThreadPoolExecutor (WRONG):

Event Loop:
[Handle Req1] [████████ GPU inference 10s ████████] [Handle Req2]
                                                      ^
                                              Req2 can't even start
                                              until Req1 finishes
                                              Health checks fail too!
```

### ThreadPoolExecutor — The Fix

```
WITH ThreadPoolExecutor(max_workers=1):

Event Loop (thread 1):
[Handle Req1] ──────────────────────────────────────► [Return Req1 result]
                    │                                         ▲
                    │ run_in_executor()                       │
                    ▼                                         │
Worker Thread:      [████████ GPU inference 10s ████████]────┘
                    
Event Loop (thread 1) during inference:
                    [Handle Req2 headers]
                    [Return /health 200]
                    [Return /ready 200]
                    [Queue Req2 for when worker is free]
```

`await loop.run_in_executor(executor, fn, arg)` means:
1. Send `fn(arg)` to the thread pool
2. **Don't block the event loop** while it runs
3. Resume this coroutine when the result is ready

### Why max_workers=1?

```
max_workers=1 (correct for 1 GPU):
Worker: [Req1 inference] [Req2 inference] [Req3 inference]
        Sequential, but event loop is free between them.
        VRAM: peaks at 1× inference memory ✓

max_workers=4 (wrong for 1 GPU):
Worker1: [Req1 inference]
Worker2: [Req2 inference]  ← these run simultaneously
Worker3: [Req3 inference]  ← VRAM = 3× inference memory → OOM
Worker4: [Req4 inference]
```

Multiple threads don't make a single GPU faster — it serializes kernel launches anyway. They just compete for VRAM and cause OOM.

---

## Monitoring GPU During Inference

### Live monitoring while server is running:

Terminal 1: `uv run uvicorn main:app`
Terminal 2: `nvidia-smi -l 1`

Watch these during a `/generate` request:
- **GPU-Util** jumps from 0% → 80-100% (GPU is working)
- **Memory-Usage** increases (activations being allocated)
- **Power** increases (GPU is drawing power)
- After request: GPU-Util drops back to 0%, memory drops (activations freed)

### Key metrics to report in an interview:

```python
# Before inference
print(f"VRAM before: {torch.cuda.memory_allocated()/1024**2:.1f} MB")

# After inference  
print(f"VRAM after: {torch.cuda.memory_allocated()/1024**2:.1f} MB")
print(f"Peak VRAM: {torch.cuda.max_memory_allocated()/1024**2:.1f} MB")

# Reset peak tracker
torch.cuda.reset_peak_memory_stats()
```

---

## Forward Pass

The **forward pass** is the computation of running inputs through a neural network to get outputs. During inference, this is all you do.

```
Forward Pass:
Input → [Layer 1] → [Layer 2] → ... → [Layer N] → Output

Each layer: output = activation(weights @ input + bias)
```

**"Refining" the forward pass** means making it faster/cheaper:

| Technique | What it does | Speedup |
|-----------|-------------|---------|
| fp16 | Halve the data size, use Tensor Cores | 1.5-2× |
| `torch.no_grad()` | Skip building computation graph | Saves VRAM |
| `torch.compile` | Fuse ops, reduce Python overhead | 1.2-1.4× |
| Attention slicing | Process attention in chunks | Saves VRAM |
| Flash Attention | Rewrite attention kernel to minimize memory bandwidth | 2-4× on attention layers |
| Quantization (INT8/INT4) | Reduce weights to 8/4 bits | 2-4× VRAM reduction |

---

## Interview Answer: "What's the difference between latency and throughput?"

> "Latency is the time a single request takes end-to-end — from when the client sends the request to when it gets the response back. Throughput is how many requests the system can complete per unit time across all users. They often trade off: batching multiple requests increases throughput because you amortize the GPU's fixed overhead, but it increases per-request latency because early requests wait for the batch to fill. For a user-facing image generation API like Luma's, I'd optimize for P99 latency first — a user waiting 30 seconds once is worse than 100 users averaging 3 seconds. For internal offline batch jobs, I'd optimize throughput instead."
