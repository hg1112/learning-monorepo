# GPU & VRAM

## CPU vs GPU — The Core Difference

A CPU is built for **sequential, low-latency tasks** — a few powerful cores that execute one instruction at a time very fast.

A GPU is built for **parallel, high-throughput tasks** — thousands of weak cores that execute the same operation on many data points simultaneously.

```
CPU                              GPU
┌─────────────────────┐          ┌─────────────────────────────────────┐
│  Core  Core  Core   │          │ ░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░ │
│  [■]   [■]   [■]   │          │ ░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░ │
│                     │          │ ░░  4096+ small CUDA cores  ░░░░░░░ │
│  Fast. Sequential.  │          │ ░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░ │
│  Few cores.         │          │ ░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░ │
└─────────────────────┘          └─────────────────────────────────────┘
  RAM (system memory)              VRAM (video memory, on GPU chip)
  DDR5: ~50 GB/s bandwidth         GDDR6: ~300+ GB/s bandwidth
```

Neural network inference = multiply millions of matrix elements simultaneously → GPU wins.

---

## What is VRAM?

VRAM (Video RAM) is memory that lives **physically on the GPU chip**. It's separate from your system RAM.

PyTorch uses VRAM for three things:

```
┌─────────────────────────────────────────────────────┐
│                    VRAM (6 GB)                      │
│                                                     │
│  ┌───────────────────────────────────────────────┐  │
│  │  Model Weights  (~2-4 GB for SD v1.5 fp16)   │  │
│  │  UNet + VAE + Text Encoder parameters         │  │
│  └───────────────────────────────────────────────┘  │
│                                                     │
│  ┌───────────────────────────────────────────────┐  │
│  │  Activations  (~0.5-1 GB during inference)   │  │
│  │  Intermediate tensors during forward pass     │  │
│  └───────────────────────────────────────────────┘  │
│                                                     │
│  ┌───────────────────────────────────────────────┐  │
│  │  PyTorch Cache  (variable)                   │  │
│  │  Freed tensors held for fast re-use           │  │
│  └───────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────┘
```

**Key rule:** If you exceed VRAM capacity → `torch.cuda.OutOfMemoryError`.

---

## PyTorch Memory API

```python
# How much VRAM is currently allocated to tensors
torch.cuda.memory_allocated()        # bytes
torch.cuda.memory_allocated() / 1024**2  # MB

# How much VRAM PyTorch has reserved (allocated + cache)
torch.cuda.memory_reserved()

# Release the cache back to CUDA (doesn't free model weights)
torch.cuda.empty_cache()

# GPU hardware properties
torch.cuda.get_device_properties(0).total_memory  # total VRAM in bytes
torch.cuda.get_device_name(0)                     # "NVIDIA GeForce GTX 1660 Ti"
```

**`empty_cache()` does NOT free model weights.** It only releases PyTorch's internal cache of freed tensors. The model stays in VRAM.

---

## nvidia-smi — Reading the Output

```
+-----------------------------------------------------------------------------------------+
| NVIDIA-SMI 595.71.05    Driver Version: 595.71.05    CUDA Version: 13.2               |
+-----------------------------------------+------------------------+----------------------+
| GPU  Name            Persistence-M      | Bus-Id    Disp.A       | Volatile Uncorr. ECC|
| Fan  Temp  Perf      Pwr:Usage/Cap      | Memory-Usage           | GPU-Util  Compute M.|
|=========================================+========================+======================|
|  0  NVIDIA GeForce GTX 1660 Ti   Off   | 00000000:01:00.0  Off  |                 N/A |
| N/A  69C   P8         1W / 60W         | 4200MiB / 6144MiB      |     87%     Default |
+-----------------------------------------+------------------------+----------------------+
     ^          ^    ^   ^       ^              ^         ^              ^
     GPU #    Temp  Perf Power             VRAM used  VRAM total    GPU utilization
```

**What each field means:**

| Field | Meaning | What to watch |
|-------|---------|---------------|
| `Temp` | GPU temperature | >85°C = thermal throttling |
| `Perf` | Performance state (P0=max, P8=idle) | Should be P0 during inference |
| `Pwr:Usage/Cap` | Power draw / limit | High = GPU is working |
| `Memory-Usage` | VRAM used / total | Watch for OOM risk |
| `GPU-Util` | % of time GPU ran a kernel | Low during inference = CPU bottleneck |

**Useful commands:**

```bash
nvidia-smi                          # snapshot
nvidia-smi -l 1                     # refresh every 1 second (live monitoring)
nvidia-smi --query-gpu=memory.used,memory.total,utilization.gpu \
           --format=csv -l 1        # CSV format, live
watch -n 1 nvidia-smi              # alternative live view
```

**During your interview:** After loading the model, run `nvidia-smi` to show the interviewer VRAM usage. It signals production awareness.

---

## CUDA vs VRAM vs Driver — What Each Term Means

| Term | What it is |
|------|-----------|
| **CUDA** | NVIDIA's parallel computing platform — the API your code uses to run on GPU |
| **VRAM** | Physical memory on the GPU chip |
| **CUDA Driver** | Kernel module that lets the OS talk to the GPU hardware |
| **CUDA Toolkit** | Libraries (cuBLAS, cuDNN) + compiler (nvcc) that build GPU programs |
| **PyTorch CUDA** | PyTorch built against a specific CUDA toolkit version |

`torch 2.12.0+cu130` means: PyTorch 2.12, compiled against CUDA 13.0 toolkit.
