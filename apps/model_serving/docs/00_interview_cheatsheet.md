# Interview Cheat Sheet — Luma AI Inference, June 16 2026

## 60-Minute Execution Plan

```
0:00 - 0:03   uv init + dependencies
0:03 - 0:08   FastAPI lifespan skeleton
0:08 - 0:18   Model loading + warmup
0:18 - 0:23   Pydantic schemas
0:23 - 0:35   run_inference() function + /generate route
0:35 - 0:40   /health + /ready endpoints
0:40 - 0:45   OOM error handling
0:45 - 0:50   Optimizations (fp16, compile, slicing)
0:50 - 1:00   Demo + questions
```

---

## Three Lines to Say Out Loud

1. **On model loading:**
   > "I'm loading the model once at startup — a 2-4GB pipeline loaded per request would make P99 latency 30+ seconds. The lifespan context manager also gives me a shutdown hook to free VRAM cleanly."

2. **On thread pool:**
   > "GPU inference is blocking CPU code. Calling it directly in an async route stalls the event loop — no health checks respond during a 20-second inference. `run_in_executor` with `max_workers=1` gives me a concurrent server with implicit GPU request queuing."

3. **On OOM:**
   > "OOM is a 503 not a 500 — the model is still alive, this specific request exceeded available VRAM. I call `empty_cache()` after catching it to release PyTorch's VRAM cache so the next request has a clean slate."

---

## Quick Reference — Concepts

### VRAM
- Memory physically on the GPU chip (separate from system RAM)
- Holds: model weights + activations + PyTorch cache
- GTX 1660 Ti = 6GB VRAM
- `torch.cuda.memory_allocated()` = currently used by tensors
- `torch.cuda.empty_cache()` = release cache (doesn't free model weights)

### fp16
- 2 bytes per parameter instead of 4 (fp32)
- SD v1.5: 4GB fp32 → 2GB fp16
- Faster on Tensor Core GPUs (1.5-2×)
- Crashes on CPU — always guard: `torch.float16 if device == "cuda" else torch.float32`

### Diffusion Model Pipeline
```
Text → [CLIP Text Encoder] → embeddings
                                  │
Random Noise → [UNet × N steps] ← ┘ → denoised latents
                                  │
               [VAE Decoder]  ← ──┘ → 512×512 image
```
- UNet runs `num_inference_steps` times (the bottleneck)
- VAE runs once (at the end)
- Text encoder runs once (at the start)
- Width/height must be multiples of 8 (VAE 8× spatial compression)

### Latency vs Throughput
- **Latency**: time for one request (optimize for user-facing APIs)
- **Throughput**: requests per second (optimize for batch processing)
- **P99**: 99th percentile latency — what the worst 1% of users experience
- Batching trades latency for throughput

### Async + Thread Pool
- FastAPI event loop = single thread, handles many connections by switching
- GPU inference = blocking → stalls event loop if called directly
- Fix: `await loop.run_in_executor(executor, fn, arg)`
- `max_workers=1` = one GPU = one worker (multiple workers → VRAM competition → OOM)

### nvidia-smi Fields
- `Temp`: GPU temperature (>85°C = throttling)
- `Memory-Usage`: VRAM used/total
- `GPU-Util`: % time GPU was running kernels (low = CPU bottleneck)
- `nvidia-smi -l 1`: live refresh every second

---

## Code Patterns — Write From Memory

### Lifespan
```python
ml_models = {}

@asynccontextmanager
async def lifespan(app: FastAPI):
    device = "cuda" if torch.cuda.is_available() else "cpu"
    dtype = torch.float16 if device == "cuda" else torch.float32
    pipe = DiffusionPipeline.from_pretrained(MODEL_ID, torch_dtype=dtype, safety_checker=None).to(device)
    pipe.enable_attention_slicing()
    pipe.vae.enable_tiling()
    if device == "cuda":
        pipe.unet = torch.compile(pipe.unet, mode="reduce-overhead", fullgraph=True)
    with torch.no_grad():
        _ = pipe("warmup", num_inference_steps=1)
    ml_models["pipeline"] = pipe
    ml_models["device"] = device
    yield
    ml_models.clear()
    torch.cuda.empty_cache()
```

### Inference
```python
def run_inference(request):
    try:
        pipe = ml_models["pipeline"]
        device = ml_models["device"]
        seed = request.seed if request.seed is not None else torch.randint(0, 2**32, (1,)).item()
        generator = torch.Generator(device=device).manual_seed(seed)
        start = time.perf_counter()
        with torch.no_grad():
            result = pipe(prompt=request.prompt, ..., generator=generator, output_type="pil")
        elapsed = (time.perf_counter() - start) * 1000
        buf = io.BytesIO()
        result.images[0].save(buf, format="PNG")
        image_b64 = base64.b64encode(buf.getvalue()).decode()
        return GenerateResponse(image_b64=image_b64, seed_used=seed, inference_time_ms=elapsed)
    except torch.cuda.OutOfMemoryError:
        torch.cuda.empty_cache()
        raise HTTPException(status_code=503, detail="GPU OOM — try smaller dimensions or fewer steps")
```

### Route
```python
executor = ThreadPoolExecutor(max_workers=1)

@app.post("/generate", response_model=GenerateResponse)
async def generate(request: GenerateRequest):
    return await asyncio.get_event_loop().run_in_executor(executor, run_inference, request)
```

### Health + Readiness
```python
@app.get("/health")
async def health():
    return {"status": "ok"}  # always 200, never check model here

@app.get("/ready")
async def ready():
    if ml_models.get("pipeline") is None:
        raise HTTPException(status_code=503, detail="Model not loaded")
    gpu_info = {}
    if ml_models.get("device") == "cuda":
        gpu_info = {
            "vram_used_mb": torch.cuda.memory_allocated() / 1024**2,
            "vram_total_mb": torch.cuda.get_device_properties(0).total_memory / 1024**2,
        }
    return {"status": "ready", "gpu": gpu_info}
```

### Pydantic Schema
```python
class GenerateRequest(BaseModel):
    prompt: str = Field(..., min_length=1, max_length=500)
    negative_prompt: str = Field(default="", max_length=500)
    num_inference_steps: int = Field(default=20, ge=1, le=100)
    guidance_scale: float = Field(default=7.5, ge=1.0, le=20.0)
    width: int = Field(default=512, ge=64, le=1024)
    height: int = Field(default=512, ge=64, le=1024)
    seed: int | None = Field(default=None)

    @field_validator("width", "height")
    @classmethod
    def must_be_multiple_of_8(cls, v):
        if v % 8 != 0:
            raise ValueError("must be multiple of 8 (VAE 8× spatial compression)")
        return v
```

---

## First Thing to Do on Interview Machine

```bash
# 1. Check GPU
nvidia-smi

# 2. Check CUDA in Python
python3 -c "import torch; print(torch.cuda.is_available(), torch.cuda.get_device_name(0))"

# 3. Init project
uv init model_serving && cd model_serving
uv add fastapi "uvicorn[standard]" diffusers transformers accelerate torch pydantic

# 4. Start coding main.py
```

---

## If Asked About Things Not Built

**Streaming:**
> "I'd use FastAPI's `StreamingResponse` with Server-Sent Events. Diffusers supports step callbacks — each UNet step can decode the intermediate latent and stream it as an SSE event. The callback runs in the thread pool, so I'd use a thread-safe `asyncio.Queue` to pass frames back to the async generator."

**Batching:**
> "Right now max_workers=1 means one request at a time. For batching, I'd implement a request queue with a configurable `batch_size` and `max_wait_ms` timeout — collect requests until the batch is full or the timeout fires, then run them as a single pipeline call with a list of prompts. This trades per-request latency for throughput."

**Model parallelism:**
> "For models too large to fit on a single GPU, you can use `accelerate` with `device_map='auto'` to split layers across multiple GPUs. Diffusers supports this natively through the `accelerate` library."
