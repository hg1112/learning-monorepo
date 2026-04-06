# Phase 8 — Triton Inference Server: Model Configs + ONNX Export

NVIDIA Triton hosts all three ML models: `user_tower`, `ad_tower`, and `dcn_ranker`. This phase covers the Triton model repository structure, `config.pbtxt` files, and how to export and verify models.

**Triton is already running** via docker-compose (started in `setup.sh`). No code to write here — just configs and scripts.

---

## Why Triton?

| Concern | Triton solution |
|---------|----------------|
| Multi-model hosting | Single server hosts user_tower + ad_tower + dcn_ranker with independent configs |
| Dynamic batching | Concurrent user_tower calls are merged into one GPU kernel automatically |
| Model versioning | Model versions live in numbered subdirectories; hot-reload via poll mode (30s) |
| Protocol | gRPC for binary tensor transfer + backpressure; HTTP for debugging |
| Metrics | Prometheus metrics at `:8002/metrics` — queue depth, latency, throughput per model |
| Backend | ONNX Runtime backend: no framework dependency in production (PyTorch CPU is 3x slower than ORT) |

---

## Model Repository Structure

```
apps/uber/ml-platform/model_repository/
├── user_tower/
│   ├── config.pbtxt        ← Triton model config
│   └── 1/
│       └── model.onnx      ← exported by STEPS_7 training script
├── ad_tower/
│   ├── config.pbtxt
│   └── 1/
│       └── model.onnx
└── dcn_ranker/
    ├── config.pbtxt
    └── 1/
        └── model.onnx
```

`setup.sh` already created these directories. After running the training scripts in STEPS_7, the `.onnx` files will be in place. Triton polls every 30 seconds and hot-loads new versions.

---

## File 1: user_tower/config.pbtxt

```protobuf
# apps/uber/ml-platform/model_repository/user_tower/config.pbtxt
name: "user_tower"
backend: "onnxruntime"
max_batch_size: 256

# Dynamic batching: Triton merges concurrent requests into one ONNX call.
# preferred_batch_size: batch sizes Triton will prefer when scheduling.
# max_queue_delay_microseconds: how long to wait for batch to fill (5ms here).
# At 1M feed loads/sec → ~1000 concurrent user_tower calls/ms → batches fill instantly.
dynamic_batching {
  preferred_batch_size: [16, 32, 64, 128]
  max_queue_delay_microseconds: 5000
}

input [
  {
    name: "input"
    data_type: TYPE_FP32
    dims: [-1, 20]   # -1 = dynamic batch dimension
  }
]

output [
  {
    name: "output"
    data_type: TYPE_FP32
    dims: [-1, 128]  # L2-normalized 128-dim embedding
  }
]

# Instance group: 1 CPU instance.
# For GPU: set kind: KIND_GPU and count: 1.
# CPU OnnxRuntime is ~2ms per call; GPU would be ~0.3ms.
# At current scale (5M daily active users), CPU is sufficient.
instance_group [
  {
    kind: KIND_CPU
    count: 2     # 2 CPU instances for parallel requests
  }
]
```

---

## File 2: ad_tower/config.pbtxt

```protobuf
# apps/uber/ml-platform/model_repository/ad_tower/config.pbtxt
name: "ad_tower"
backend: "onnxruntime"
max_batch_size: 64

# Ad tower runs OFFLINE (triggered by ad-created-events), not per request.
# No dynamic batching needed — ads are created one at a time.
# Smaller max_batch_size: 64 ads could be created in a burst.

input [
  {
    name: "input"
    data_type: TYPE_FP32
    dims: [-1, 16]   # 16-dim ad feature vector
  }
]

output [
  {
    name: "output"
    data_type: TYPE_FP32
    dims: [-1, 128]  # 128-dim ad embedding → stored in Qdrant
  }
]

instance_group [
  {
    kind: KIND_CPU
    count: 1
  }
]
```

---

## File 3: dcn_ranker/config.pbtxt

```protobuf
# apps/uber/ml-platform/model_repository/dcn_ranker/config.pbtxt
name: "dcn_ranker"
backend: "onnxruntime"
max_batch_size: 512

# DCN ranker processes all 100 candidates in one call per feed load.
# Each call is already a batch of 100 rows [100, 32].
# Dynamic batching merges CONCURRENT feed loads into one larger batch.
# At 10K feed loads/sec → 10K × 100 rows = 1M rows/sec through dcn_ranker.
# With GPU and dynamic batching, a single A100 handles ~10M rows/sec.
dynamic_batching {
  preferred_batch_size: [100, 200, 400]
  max_queue_delay_microseconds: 2000   # 2ms max wait
}

input [
  {
    name: "input"
    data_type: TYPE_FP32
    dims: [-1, 32]   # [batch × candidates, 32 features]
  }
]

output [
  {
    name: "output"
    data_type: TYPE_FP32
    dims: [-1]       # flat array of CTR scores, one per row
  }
]

instance_group [
  {
    kind: KIND_CPU
    count: 4    # 4 CPU instances: dcn_ranker is the hot path
  }
]
```

---

## Exporting Models (already done in STEPS_7, shown here for reference)

The training scripts in `models/two_tower.py` and `models/dcn_ranker.py` export ONNX at the end. Explicit export commands:

```python
# Standalone export script (if models are already trained)
# apps/uber/ml-platform/export_models.py

import torch
import mlflow.pytorch

# Load from MLflow model registry
user_tower = mlflow.pytorch.load_model("models:/two_tower/latest")
dcn_ranker = mlflow.pytorch.load_model("models:/dcn_ranker/latest")

# Export user tower
dummy = torch.randn(1, 20)
torch.onnx.export(
    user_tower.user_tower, dummy,
    "model_repository/user_tower/1/model.onnx",
    input_names=["input"], output_names=["output"],
    dynamic_axes={"input": {0: "batch"}, "output": {0: "batch"}},
    opset_version=17,
)

# Export ad tower
dummy = torch.randn(1, 16)
torch.onnx.export(
    user_tower.ad_tower, dummy,
    "model_repository/ad_tower/1/model.onnx",
    input_names=["input"], output_names=["output"],
    dynamic_axes={"input": {0: "batch"}, "output": {0: "batch"}},
    opset_version=17,
)

# Export dcn_ranker
dummy = torch.randn(1, 32)
torch.onnx.export(
    dcn_ranker, dummy,
    "model_repository/dcn_ranker/1/model.onnx",
    input_names=["input"], output_names=["output"],
    dynamic_axes={"input": {0: "batch"}, "output": {0: "batch"}},
    opset_version=17,
)

print("All models exported.")
```

---

## Verification

```bash
# 1. Check Triton is running and models are loaded
curl http://localhost:8000/v2/health/ready
curl http://localhost:8000/v2/models/user_tower
curl http://localhost:8000/v2/models/dcn_ranker

# 2. Test user_tower inference (HTTP, for debugging)
curl -X POST http://localhost:8000/v2/models/user_tower/infer \
  -H "Content-Type: application/json" \
  -d '{
    "inputs": [{
      "name": "input",
      "shape": [1, 20],
      "datatype": "FP32",
      "data": [0.1,0.2,0.3,0.4,0.5,0.0,0.0,0.0,0.05,0.3,0.5,0.7,-0.7,0.4,-0.9,0.0,0.0,0.3,0.2,0.0]
    }]
  }'
# Expect: output shape [1, 128], values near unit sphere (L2 norm ≈ 1.0)

# 3. Check Prometheus metrics
curl http://localhost:8002/metrics | grep nv_inference

# 4. Check Triton logs for model load
docker logs uber-triton

# 5. After a model update: put new model.onnx in model_repository/user_tower/2/
#    Triton polls every 30s and loads it. No restart needed.
#    Version 1 continues serving until version 2 is loaded.
```

---

## Model Versioning

```
# To deploy a new dcn_ranker version:
mkdir -p model_repository/dcn_ranker/2
cp new_model.onnx model_repository/dcn_ranker/2/model.onnx
# Triton picks it up in ≤30s. Version 1 is still served until v2 is ready.

# config.pbtxt version_policy controls which versions are live:
# Default (no policy): only latest version served.
# To serve all: add version_policy { all {} }
# To serve specific: version_policy { specific { versions: [1, 2] } }
```

Continue to `STEPS_9.md` for Phase 9 — Feed BFF (GraphQL).
