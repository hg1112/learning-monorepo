# Interview Q&A — Self-Paced Test

**How to use:** Cover the answer, say your answer out loud, then check.
Questions are ordered from most likely to least likely to come up.

---

## SECTION 1 — Diffusion Models (Leon's core domain — expect depth here)

---

**Q1. What is a diffusion model? Explain it in 2 minutes.**

A: A diffusion model learns to reverse a noise process. During training, you gradually add Gaussian noise to real images over T timesteps until they become pure noise, and train a neural network (UNet) to predict and remove the noise at each step. During inference, you start from pure random noise and iteratively denoise it using the trained UNet, guided by a text prompt. The UNet operates in a compressed latent space — the VAE encoder compresses images 8× spatially, the UNet runs in that small space for all N denoising steps, and the VAE decoder expands latents back to pixels at the very end.

---

**Q2. What are the four components of a Stable Diffusion pipeline and what does each one do?**

A:
- **Text Encoder (CLIP):** Converts the text prompt to a vector embedding that the UNet uses via cross-attention. Runs once.
- **UNet:** The denoiser. Takes noisy latents + text embeddings + timestep, predicts the noise to remove. Runs `num_inference_steps` times — this is the bottleneck.
- **Scheduler:** Controls the math of noise removal at each step (DDPM, DDIM, DPM++). Determines the timestep sequence and how to update latents.
- **VAE:** Variational Autoencoder. Encoder compresses pixels → latents (used in training/img2img). Decoder expands latents → pixels (used at end of every inference call).

---

**Q3. Why must image width and height be multiples of 8?**

A: The VAE compresses images by 8× in each spatial dimension. A 512×512 image becomes a 64×64 latent. If you pass 513×512, the VAE would need a 64.125×64 tensor — which is impossible. The result is a cryptic shape mismatch error deep in the model. Catching it at the Pydantic validation layer (with `@field_validator`) gives users a clean 422 error instead.

---

**Q4. What is `num_inference_steps` and what happens if you set it to 1 vs 50?**

A: It controls how many times the UNet runs. Each step removes some noise from the latent. With 1 step: inference is fast but image quality is very poor — too much noise remains. With 50 steps: high quality but 50× slower than 1 step. The sweet spot for most schedulers is 20-30 steps. Some modern schedulers (LCM, SDXL-Turbo) are specifically trained for 4-8 steps.

---

**Q5. What is `guidance_scale` and what does it control?**

A: Classifier-free guidance scale. Controls how strongly the image follows the text prompt. Internally, the UNet runs twice per step — once with the text prompt and once with an empty/null prompt — and the outputs are interpolated:

`noise_pred = uncond + guidance_scale * (cond - uncond)`

Higher scale = more literal adherence to the prompt, but can oversaturate. Lower scale = more creative/varied output. Typical range: 7-8. Setting it to 1.0 effectively disables guidance.

---

## SECTION 2 — GPU & VRAM

---

**Q6. What is VRAM and how is it different from system RAM?**

A: VRAM (Video RAM) is memory physically on the GPU chip, separate from system RAM. It has much higher bandwidth (~300+ GB/s vs ~50 GB/s for DDR5) because the GPU needs to move huge amounts of data to its thousands of cores simultaneously. PyTorch uses VRAM for three things: model weights (the largest, persistent), intermediate activations during the forward pass (temporary), and a cache of recently freed tensors it holds for fast re-allocation.

---

**Q7. What does `torch.cuda.empty_cache()` do? Does it free the model from VRAM?**

A: No. `empty_cache()` releases PyTorch's internal cache of freed tensors back to CUDA — tensors that were deleted but still held in a cache to avoid expensive re-allocation. The model weights are not touched — they stay in VRAM as long as you hold a reference to the pipeline object. After catching a CUDA OOM, calling `empty_cache()` is important so the next request has access to that cached memory. Without it, the next request may OOM even if it's smaller.

---

**Q8. How do you monitor GPU utilization during inference?**

A: `nvidia-smi -l 1` refreshes every second. Key fields: `Memory-Usage` shows VRAM used/total, `GPU-Util` shows what percentage of time the GPU was running kernels (if it's low during inference, you have a CPU bottleneck — the GPU is waiting for data), `Temp` shows temperature (>85°C causes throttling), `Pwr:Usage` shows power draw. In Python: `torch.cuda.memory_allocated()` for current allocation, `torch.cuda.max_memory_allocated()` for peak during a run.

---

**Q9. Your server has been running for a day and VRAM usage keeps climbing. What's happening and how do you fix it?**

A: VRAM leak. Likely causes:
1. Tensors are being created and not freed — check if intermediate tensors are accidentally stored in a list or dict that grows over time.
2. `torch.no_grad()` is missing — computation graphs are being built and held in memory each inference.
3. The PyTorch cache is growing — call `empty_cache()` periodically or after each request.

Debug with `torch.cuda.memory_summary()` — it shows exactly what's allocated and where. Fix: ensure `torch.no_grad()` wraps all inference, don't store tensors globally, and call `empty_cache()` if cache growth is the issue.

---

## SECTION 3 — Precision (fp16/fp32)

---

**Q10. Why do you use fp16 for inference? What are the trade-offs?**

A: fp16 halves VRAM usage (2 bytes vs 4 per parameter). For SD v1.5 that's ~4GB → ~2GB, which either fits in VRAM where fp32 wouldn't, or leaves headroom for larger activations. On Tensor Core GPUs, fp16 matrix multiplications are 1.5-2× faster because Tensor Cores are specialized for fp16. Trade-off: fp16 has a narrower numerical range (max ~65504) and lower precision. For inference this rarely causes issues — we're doing forward passes only, no gradient accumulation where overflow is common.

---

**Q11. Why do you guard fp16 with a device check? What happens if you use fp16 on CPU?**

A: `dtype = torch.float16 if device == "cuda" else torch.float32`. CPUs have no hardware Tensor Core equivalent for fp16. PyTorch has no optimized CPU fp16 kernels for many operations — some will raise `RuntimeError`, others silently fall back to fp32 (slower than just using fp32 directly). The guard ensures you get fp16 speed on GPU and stable fp32 on CPU without code changes.

---

**Q12. What is bf16 and when would you use it instead of fp16?**

A: bf16 (brain float 16) is also 16 bits but keeps fp32's full exponent range while reducing mantissa precision. fp16's narrow range (max 65504) can cause overflow during gradient accumulation in training. bf16 avoids this at the cost of less mantissa precision. For inference: bf16 is preferred on Ampere+ GPUs (A100, RTX 3000+) that support it natively, since it's safer than fp16 with similar performance. GTX 1660 Ti (Turing) doesn't support bf16 natively, so fp16 is the right choice here.

---

## SECTION 4 — Serving Architecture

---

**Q13. Why do you use `ThreadPoolExecutor` instead of calling `run_inference` directly in the async route?**

A: FastAPI's event loop is single-threaded. GPU inference is a blocking CPU call that takes seconds. If you call `pipe(...)` directly inside `async def generate(...)`, you block the entire event loop for the duration of inference — no other request can be handled, including health checks and readiness probes. `run_in_executor` offloads the blocking work to a thread, freeing the event loop to handle other connections while inference runs. `max_workers=1` because you have one GPU — multiple workers would compete for VRAM and cause OOM.

---

**Q14. Why `max_workers=1` specifically? What if you set it to 4?**

A: You have one GPU. Setting max_workers=4 would let 4 threads submit inference work simultaneously. CUDA serializes kernel launches on a single GPU anyway — you get no parallelism. Worse, each thread would try to allocate VRAM for its full inference run simultaneously, leading to OOM. The implicit queue in the ThreadPoolExecutor with max_workers=1 is the right design — requests wait in line rather than crashing into each other.

---

**Q15. What is the difference between `/health` and `/ready` endpoints? What happens when each fails in Kubernetes?**

A: These map to Kubernetes liveness and readiness probes.
- `/health` (liveness): "Is the process alive?" If it fails, K8s **restarts the pod**. It must always return 200 if the process is running — never check model state here. If model loading fails and you check it in `/health`, K8s restarts → model fails to load → restarts → infinite loop.
- `/ready` (readiness): "Is the pod ready to accept traffic?" If it fails, K8s **removes the pod from the load balancer** but does NOT restart it. Return 503 during model loading; return 200 only after the pipeline is loaded and warm. This prevents traffic from hitting the pod before it can serve.

---

**Q16. A user sends `width=1024, height=1024, num_inference_steps=100`. Your server OOMs. What HTTP status do you return and why? What do you do after?**

A: Return 503 (Service Unavailable), not 500. 500 means "my code is broken." 503 means "I can't handle this request right now — try again with different parameters." The model is still alive and can serve future requests. After catching `torch.cuda.OutOfMemoryError`, call `torch.cuda.empty_cache()` to release PyTorch's cached VRAM so the next request has a clean slate. The error detail should hint at the fix: "GPU OOM — try smaller dimensions or fewer steps."

---

## SECTION 5 — Optimizations

---

**Q17. What does `torch.compile` do and what is the cost?**

A: `torch.compile` traces the model's computation graph and compiles it to optimized CUDA kernels — fusing multiple Python-level operations into single GPU kernel launches. This reduces Python dispatch overhead and enables cross-op optimizations. The cost: JIT compilation on first call adds 30-90 seconds to startup time. With `mode="reduce-overhead"` (best for latency-sensitive serving) vs `mode="max-autotune"` (best for throughput, takes several minutes to compile).

---

**Q18. Why do you only compile `pipe.unet` and not the whole pipeline?**

A: The UNet runs `num_inference_steps` times (e.g., 20 times) per inference call. The text encoder and VAE each run once. Compiling the UNet amortizes the 30-60s compile cost across every inference call — high ROI. Compiling the text encoder or VAE pays the same startup cost for an operation that runs once and takes milliseconds. Not worth it.

---

**Q19. What is attention slicing and when do you enable it?**

A: The UNet's attention layers compute attention across all spatial positions simultaneously. The attention matrix scales as O(seq_len²) — for a 512×512 image this is manageable, but at 768×768+ it becomes the primary VRAM consumer. Attention slicing processes the attention matrix in chunks, trading VRAM for slightly more compute. Enable it when: VRAM < 8GB, or generating images at 768×768+. Disable it when: VRAM is plentiful and you need maximum speed at standard resolutions.

---

**Q20. What is VAE tiling and when do you enable it?**

A: The VAE decoder processes the full latent image in one pass. For very high-resolution outputs (1024×1024+), the VAE decode alone can OOM. VAE tiling splits the image into spatial tiles, decodes each independently, and stitches the results. Enable it only for high-resolution outputs — at 512×512 or 768×768 it adds stitching overhead with no benefit and can introduce subtle tile boundary artifacts.

---

## SECTION 6 — System Design (Luma-specific scale questions)

---

**Q21. How would you scale this server to handle 1000 concurrent users?**

A: Several layers:
1. **Multiple GPU instances** behind a load balancer — each instance runs one server with `max_workers=1`. Route requests round-robin or by GPU utilization.
2. **Request queue** — use a message queue (Redis, SQS) to decouple request acceptance from processing. Users get a job ID immediately, poll for completion.
3. **Dynamic batching** — collect requests arriving within a time window (e.g., 100ms) and run them as a batch. Amortizes fixed GPU overhead.
4. **Auto-scaling** — scale GPU instances based on queue depth.
5. **Model caching** — keep the model loaded in VRAM persistently, never unload between requests.

---

**Q22. How would you implement dynamic batching in this server?**

A: Replace the ThreadPoolExecutor with a batching queue:
1. Incoming requests add themselves to a shared queue with a `Future` to wait on
2. A background worker collects requests up to `max_batch_size` OR `max_wait_ms` timeout
3. Run the pipeline with a list of prompts: `pipe(prompts=["cat", "dog", ...])`
4. Split the batch result and resolve each request's Future

Trade-off: latency increases by up to `max_wait_ms` for every request, but throughput increases proportionally to batch size.

---

**Q23. How would you deploy this to production with Docker and Kubernetes?**

A: 
1. **Dockerfile**: `FROM nvidia/cuda:13.2-runtime-ubuntu22.04`, install Python + uv, copy code, `CMD ["uv", "run", "uvicorn", "main:app", "--host", "0.0.0.0"]`
2. **K8s Deployment**: request GPU resource `resources.limits: nvidia.com/gpu: 1`, set `livenessProbe` on `/health`, `readinessProbe` on `/ready` (with `initialDelaySeconds` long enough for model load + warmup)
3. **HorizontalPodAutoscaler**: scale on GPU utilization or custom queue-depth metric
4. **Model storage**: mount models from persistent volume or pull from S3 on startup (not baked into image — models are too large)

---

**Q24. How would you reduce the cold start time when a new pod spins up?**

A: Cold start = time from pod start to first served request. Bottlenecks: model download + model load + warmup.
1. **Pre-pull model weights** — use a Kubernetes init container or a shared persistent volume to pre-populate the HuggingFace cache. Avoids downloading 4GB on every pod start.
2. **Reduce warmup steps** — warmup with `num_inference_steps=1` (we already do this).
3. **Lazy compile** — skip `torch.compile` on startup, compile on first real request (amortizes compile cost differently).
4. **Checkpoint the compiled model** — `torch.compile` supports saving compiled artifacts so you don't recompile on every restart.
5. **Keep-warm pool** — maintain a minimum pod count so you always have capacity and cold starts don't affect users.

---

**Q25. A request is taking 60 seconds instead of the expected 5 seconds. How do you diagnose it?**

A: Systematic diagnosis:
1. `nvidia-smi -l 1` during the slow request — is GPU-Util near 100%? If yes, inference is actually running slow (might be throttling from heat, or model is doing extra work). If GPU-Util is 0%, GPU isn't being used — might be stuck in data loading, tokenization, or the model is on CPU.
2. Add timing logs around each pipeline component: text encoding, UNet steps, VAE decode.
3. Check if `torch.compile` triggered a recompile — happens if input shapes change (different width/height than warmup). Recompile logs show in stdout.
4. Check if the model accidentally fell back to CPU — `pipe.device` and `pipe.unet.device`.
5. Check system memory (not VRAM) — if system RAM is full, swap is causing slowdowns.

---

## SECTION 7 — Code Reading Questions

---

**Q26. What's wrong with this code?**
```python
@app.post("/generate")
async def generate(request: GenerateRequest):
    with torch.no_grad():
        result = pipe(prompt=request.prompt, output_type="pil")
    return result.images[0]
```

A: Three problems:
1. Calling `pipe(...)` directly in an async route blocks the event loop. Should use `run_in_executor`.
2. Returning a PIL Image directly — it's not JSON serializable. Must convert to base64.
3. `pipe` is a bare global — if this runs before lifespan finishes, `pipe` is undefined. Should use `ml_models["pipeline"]`.

---

**Q27. What's wrong with this code?**
```python
@asynccontextmanager
async def lifespan(app: FastAPI):
    device = "cuda" if torch.cuda.is_available() else "cpu"
    pipe = DiffusionPipeline.from_pretrained("model", torch_dtype=torch.float16).to(device)
    ml_models["pipeline"] = pipe
    yield
```

A: Two problems:
1. `torch.float16` hardcoded — will crash if device is CPU.
2. No shutdown path after `yield` — VRAM is never freed. Should call `ml_models.clear()` and `torch.cuda.empty_cache()` after yield.

---

**Q28. What's wrong with this code?**
```python
seed = request.seed if request.seed else random.randint(0, 2**32)
generator = torch.Generator(device="cpu").manual_seed(seed)
```

A: Two problems:
1. `if request.seed` is falsy for `seed=0`. Zero is a valid seed — should be `if request.seed is not None`.
2. Generator is on CPU but model is on CUDA. PyTorch silently ignores a CPU generator when running on CUDA — results will be non-reproducible even if the user passes a seed. Should be `torch.Generator(device=device)`.

---

**Q29. Why would you add `safety_checker=None` when loading the pipeline? Isn't that irresponsible?**

A: In a controlled/internal serving context, the safety checker loads an additional CLIP model (~600MB) and runs it on every generated image. This costs VRAM and adds latency. For an internal API where inputs are trusted, it's reasonable to disable it and save the resources. For a public-facing endpoint where arbitrary user prompts are accepted, you'd keep it enabled or implement your own content moderation at the API layer (which gives you more control anyway). The honest answer: it's a deliberate trade-off, not negligence.

---

## SECTION 8 — Quick-Fire Round

**Q:** What does `output_type="pil"` do?
**A:** Tells Diffusers to return a PIL Image object. Alternatives: `"np"` (numpy array), `"latent"` (skip VAE decode, return raw latents).

**Q:** What does `time.perf_counter()` do that `time.time()` doesn't?
**A:** Nanosecond resolution and doesn't drift with NTP clock adjustments. Better for precise latency measurement.

**Q:** Why do you store both `pipe` and `device` in `ml_models`?
**A:** The inference function needs the device to create a device-matched `torch.Generator`. Without storing it, you'd call `torch.cuda.is_available()` again, which is fine but redundant — the lifespan already determined the device.

**Q:** What is a lifespan context manager vs `@app.on_event("startup")`?
**A:** `on_event` is deprecated. Lifespan puts startup and shutdown in one function with shared local scope, separated by `yield`. Cleaner, and correctly handles exceptions in startup.

**Q:** What happens to an exception raised inside `run_in_executor`?
**A:** It's captured and re-raised in the calling coroutine when you `await` the result. So an `HTTPException` raised inside `run_inference` will propagate back through `await loop.run_in_executor(...)` and FastAPI will handle it normally.

**Q:** Luma builds video models (Ray3). How does video generation differ from image generation?
**A:** Video adds a temporal dimension. The UNet or DiT operates over sequences of frames, not single images. VRAM requirements are much higher (T frames × spatial dimensions). Inference is much slower. Typical approach: generate keyframes, then interpolate. Streaming intermediate frames to the user (progressive rendering) is more important since latency is very high.
