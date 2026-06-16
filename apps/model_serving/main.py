from contextlib import asynccontextmanager
from concurrent.futures import ThreadPoolExecutor
import asyncio
import time
import torch

import io
import base64
from diffusers import DiffusionPipeline
from fastapi import FastAPI, HTTPException

from schemas import GenerateResponse, GenerateRequest

ml_models = {}

executor = ThreadPoolExecutor(max_workers=1)


@asynccontextmanager
async def lifespan(app: FastAPI):
    print("Loading Model .. ")

    gpu_available = torch.cuda.is_available()
    device = "cuda" if gpu_available else "cpu"
    dtype = torch.float16 if gpu_available else torch.float32

    pipe = DiffusionPipeline.from_pretrained(
        "hf-internal-testing/tiny-stable-diffusion-pipe",
        torch_dtype=dtype,
        safety_checker=None
    ).to(device)

    pipe.enable_attention_slicing()
    pipe.enable_vae_tiling()

    if device == "cuda":
        pipe.unet = torch.compile(pipe.unet, mode="reduce-overhead", fullgraph=True)

    with torch.no_grad():
        _ = pipe("warmup", num_inference_steps=1)

    ml_models['pipeline'] = pipe
    ml_models['device'] = device

    yield

    print("Cleaning up ...")
    ml_models.clear()
    torch.cuda.empty_cache()


app = FastAPI(lifespan=lifespan)


def run_inference(request):
    try:
        pipe = ml_models['pipeline']
        device = ml_models['device']

        seed = request.seed if request.seed is not None else torch.randint(0, 2 ** 32, (1,)).item()
        generator = torch.Generator(device=device).manual_seed(seed)

        start = time.perf_counter()
        with torch.no_grad():
            result = pipe(
                prompt=request.prompt,
                negative_prompt=request.negative_prompt,
                num_inference_steps=request.num_inference_steps,
                guidance_scale=request.guidance_scale,
                width=request.width,
                height=request.height,
                generator=generator,
                output_type="pil"
            )
        elapsed = (time.perf_counter() - start) * 1000
        image = result.images[0]

        buf = io.BytesIO()
        image.save(buf, format='PNG')
        image_b64 = base64.b64encode(buf.getvalue()).decode()

        return GenerateResponse(
            image_b64=image_b64,
            seed_used=seed,
            inference_time_ms=elapsed,
        )
    except torch.cuda.OutOfMemoryError as e:
        torch.cuda.empty_cache()
        raise HTTPException(status_code=503, detail=str(e))


@app.post("/generate", response_model=GenerateResponse)
async def generate(request: GenerateRequest):
    loop = asyncio.get_event_loop()
    return await loop.run_in_executor(executor, run_inference, request)


@app.get("/health")
async def health():
    return {"status": "ok"}


@app.get("/ready")
async def ready():
    if 'pipeline' in ml_models and ml_models['pipeline'] is not None:
        gpu_info = {}
        if ml_models.get("device") == "cuda":
            gpu_info = {
                "vram_used_mb": torch.cuda.memory_allocated() / 1024 ** 2,
                "vram_total_mb": torch.cuda.get_device_properties(0).total_memory / 1024 ** 2,
            }
        return {"status": "ready", "gpu": gpu_info}

    else:
        raise HTTPException(status_code=503)


@app.get("/")
async def root():
    return {"status": "ok"}
