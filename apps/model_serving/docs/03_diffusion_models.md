# Diffusion Models

## The Core Idea — Denoising

A diffusion model learns to **reverse noise**. That's it.

**Training (you don't do this, but know it):**
1. Take a real image
2. Gradually add random Gaussian noise over T steps until it's pure noise
3. Train a neural network to predict and remove the noise at each step

**Inference (what you're serving):**
1. Start with pure random noise
2. Run the model T times, each time removing a little noise
3. End up with a coherent image

```
Inference (what the server does):

Pure Noise          Step 1           Step 10          Step 20        Final Image
┌─────────┐        ┌─────────┐      ┌─────────┐      ┌─────────┐    ┌─────────┐
│▓▓░▓░▓▓░│  UNet  │▒▒░▒░▒▒░│ ...  │▒░ cat ░▒│ ...  │░ cat  ░│    │  cat   │
│░▓▓░▓░▓▓│ ──────►│░▒▒░▒░▒▒│      │▒▒ shape ▒│      │  shape  │    │        │
│▓░▓▓░▓░▓│        │▒░▒▒░▒░▒│      │▒░       ▒│      │         │    │        │
└─────────┘        └─────────┘      └─────────┘      └─────────┘    └─────────┘

num_inference_steps = 20 means the UNet runs 20 times
```

**`num_inference_steps`** — more steps = better quality but slower. 20-50 is typical.
**`guidance_scale`** — how strongly the image follows your text prompt. Higher = more literal.

---

## The Four Pipeline Components

A Stable Diffusion pipeline has 4 components. Each does a specific job:

```
┌─────────────────────────────────────────────────────────────────────┐
│                    Stable Diffusion Pipeline                        │
│                                                                     │
│  "a red cat"                                                        │
│       │                                                             │
│       ▼                                                             │
│  ┌─────────────────┐                                                │
│  │  Text Encoder   │  Converts text → vector of numbers            │
│  │  (CLIP)         │  "a red cat" → [0.3, -0.7, 0.2, ...]          │
│  └────────┬────────┘                                                │
│           │ text embeddings                                         │
│           ▼                                                         │
│  ┌─────────────────┐    ┌──────────────────┐                       │
│  │     UNet        │◄───│    Scheduler     │                       │
│  │                 │    │                  │                       │
│  │  The denoiser.  │    │  Controls the    │                       │
│  │  Runs N times.  │    │  noise removal   │                       │
│  │  ~860M params   │    │  schedule (DDPM, │                       │
│  │  90% of time    │    │  DDIM, DPM++)    │                       │
│  └────────┬────────┘    └──────────────────┘                       │
│           │ denoised latents                                        │
│           ▼                                                         │
│  ┌─────────────────┐                                                │
│  │      VAE        │  Decoder: latents → pixels                    │
│  │   (Decoder)     │  64×64 → 512×512 image                        │
│  └────────┬────────┘                                                │
│           │                                                         │
│           ▼                                                         │
│      PIL Image                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

---

## Component 1: Text Encoder (CLIP)

Converts your text prompt into a numerical representation the UNet can use.

```python
# What it does internally:
"a red cat"
    → tokenize → [49406, 320, 736, 2368, 49407]
    → embed    → tensor of shape [77, 768]  # 77 tokens, 768-dim embedding
```

- Runs **once** per inference call
- Output is passed to every UNet layer via cross-attention
- This is how the UNet "knows" what to generate

---

## Component 2: UNet (The Denoiser)

The biggest, slowest part. It takes:
- A noisy latent image
- The text embedding
- The current timestep (how noisy we are)

And outputs: a slightly less noisy latent.

```
Runs num_inference_steps times (e.g., 20 times for 20-step inference)

Step 20 (very noisy) → Step 19 → ... → Step 1 → Step 0 (clean)
```

This is why `num_inference_steps` directly controls inference time — more steps = more UNet forward passes = slower.

The UNet uses **cross-attention** to incorporate the text embedding. This is also the most VRAM-intensive operation — attention scales quadratically with image resolution.

---

## Component 3: VAE (Variational Autoencoder)

The UNet doesn't work in pixel space — it works in **latent space** (compressed representations).

```
Pixel space:  512 × 512 × 3  =  786,432 values
Latent space:  64 ×  64 × 4  =   16,384 values   ← 48× smaller!

Compression factor: 8× in each spatial dimension
```

The VAE has two halves:
- **Encoder** — used during training/image-to-image: compresses pixels → latents
- **Decoder** — used during inference: expands latents → pixels (runs once at the end)

**Why width/height must be multiples of 8:**
The VAE compresses by 8× in each dimension. 512 → 64 (clean). 513 → 64.125 (impossible tensor shape → crash).

---

## Component 4: Scheduler

Controls the math behind noise removal at each step. Different schedulers = different quality/speed tradeoffs.

| Scheduler | Steps needed | Quality |
|-----------|-------------|---------|
| DDPM | 1000 | Highest |
| DDIM | 20-50 | Good |
| DPM++ 2M | 15-25 | Good, faster |
| LCM | 4-8 | Fast, lower quality |

You rarely need to change the scheduler in an interview — Diffusers picks a good default.

---

## Inference Flow — Full Picture

```
Request: {"prompt": "a red cat", "num_inference_steps": 20}

1. Text Encoder runs once:
   "a red cat" → text_embeddings [1, 77, 768]

2. Scheduler generates initial noise:
   latents = torch.randn(1, 4, 64, 64)  # for 512×512 output

3. UNet loop (runs 20 times):
   for t in scheduler.timesteps:         # t = 999, 949, ..., 0
       noise_pred = unet(latents, t, text_embeddings)
       latents = scheduler.step(noise_pred, t, latents).prev_sample

4. VAE Decoder runs once:
   image = vae.decode(latents / 0.18215).sample  # 64×64 → 512×512

5. Post-process:
   image = (image / 2 + 0.5).clamp(0, 1)  # normalize to [0, 1]
   image = PIL.Image.fromarray((image * 255).byte().numpy())
```

---

## Key Numbers to Know

| Thing | Value |
|-------|-------|
| SD v1.5 VRAM (fp16) | ~4 GB |
| SD v1.5 VRAM (fp32) | ~8 GB |
| Latent size for 512×512 | 64×64×4 |
| Text encoder output | 77×768 |
| UNet parameter count | ~860M |
| Typical inference steps | 20-50 |
| VAE spatial compression | 8× |

---

## Interview Answer: "What is a diffusion model?"

> "A diffusion model is a generative model that learns to reverse a noise process. During training, you gradually corrupt images with Gaussian noise until they're unrecognizable, and train a neural network — typically a UNet — to predict and remove that noise at each step. During inference, you start with pure random noise and iteratively denoise it using the trained UNet, guided by a text prompt encoded via a CLIP text encoder. The UNet operates in a compressed latent space managed by a VAE — this is what makes it computationally feasible. The VAE compresses 512×512 images to 64×64 latents, the UNet runs in that small space, and the VAE decoder expands back to pixels at the very end."
