# Chapter 18 — element-davinci (Triton ML Inference Server)

## 1. Overview

**element-davinci** is the **NVIDIA Triton Inference Server** deployment that powers ML inference for the Sponsored Products ad serving pipeline. It hosts ensemble ML models on **NVIDIA H100 GPUs**, serving **embedding vectors** and **relevance scores** via gRPC to the `davinci` Java service (Chapter 15), which acts as a caching and routing layer on top of it.

- **Domain:** ML Model Serving Infrastructure
- **Tech:** NVIDIA Triton 24.12 (Python 3), PyTorch, ONNX, Transformers, CLIP
- **GPU:** NVIDIA H100 (80GB)
- **Replicas:** 16 (prod), 1 (dev/stage)
- **WCNP Namespace:** `ss-davinci-wmt`
- **Element Project ID:** 14820
- **APM ID:** APM0007658

---

## 2. Architecture Diagram

```mermaid
graph LR
  subgraph element_davinci["element-davinci (NVIDIA Triton 24.12)"]
    TRITON["tritonserver\n--model-repository=/models\n--log-verbose=1"]

    subgraph models["ML Models (/models/)"]
      EMB["ensemble_ttb_emb / v2_2\n(Text-to-Bytes Embeddings)"]
      REL_V1["ensemble_model_relevance_v1\n(Relevance Scoring)"]
      UNIV_R1["universal_r1_ensemble_model_relevance_v1\n(Universal Relevance)"]
      RETRIEVAL["search_page_comp_retrieval_ensemble_model_v1\n(Retrieval)"]
      MULTIMODAL["multimodal_v2_ensemble_model\n(CLIP Multi-modal)"]
      TOKENIZER["item_middle_retrieval_model_tokenizer\n(Tokenization)"]
    end

    TRITON --> EMB
    TRITON --> REL_V1
    TRITON --> UNIV_R1
    TRITON --> RETRIEVAL
    TRITON --> MULTIMODAL
    TRITON --> TOKENIZER
  end

  DAVINCI_JAVA["davinci\n(Java Spring Boot)\nCaching + routing layer"]
  AZURE_BLOB[("Azure Blob Storage\nss-inference-models/\nprod_inference_models/")]
  AUTOMATON["element-davinci-automaton\n(JMeter perf testing)"]

  DAVINCI_JAVA -->|"gRPC v2/models/{model}/infer\nBearer token auth\nPort 8001"| TRITON
  TRITON -->|"Load models at startup\nwasbs://ss-inference-models/..."| AZURE_BLOB
  AUTOMATON -->|"HTTPS POST /v2/models/ensemble_ttb_emb/infer\n(perf testing)"| TRITON
```

---

## 3. API / Interface

**Triton V2 Inference Protocol (HTTP + gRPC):**

| Protocol | Port | Path | Description |
|----------|------|------|-------------|
| gRPC | 8001 | `ModelInfer` | Primary inference (used by davinci Java) |
| HTTP | 8000 | `POST /v2/models/{model}/infer` | REST inference endpoint |
| Metrics | 8002 | `GET /metrics` | Prometheus metrics scraping |
| Health | 8000 | `GET /v2/health/live` | Liveness probe |
| Health | 8000 | `GET /v2/health/ready` | Readiness probe |

**Example HTTP Request (ensemble_ttb_emb):**
```json
POST /v2/models/ensemble_ttb_emb/infer
Authorization: Bearer {tritonAuthToken}

{
  "inputs": [{
    "name": "TEXT",
    "shape": [1],
    "datatype": "BYTES",
    "data": ["Dell Laptop 15 inch Intel Core i7"]
  }],
  "outputs": [{
    "name": "output-0",
    "parameters": { "binary_data": false }
  }]
}
```

**Response:**
```json
{
  "model_name": "ensemble_ttb_emb",
  "outputs": [{
    "name": "output-0",
    "shape": [1, 512],
    "datatype": "FP32",
    "data": [0.024, -0.183, 0.091, ...]
  }]
}
```

---

## 4. ML Models

```mermaid
graph TD
  subgraph ensemble_ttb_emb["ensemble_ttb_emb / v2_2 (Text Embedding)"]
    T1["Tokenizer\n(text preprocessing)"]
    E1["TTB Embedding Model\n(ONNX — TensorRT)"]
    T1 --> E1
  end

  subgraph rel_v1["ensemble_model_relevance_v1 (Relevance)"]
    T2["Tokenizer"]
    Q_ENC["Query Encoder"]
    ITEM_ENC["Item Encoder"]
    SCORE["Score Head"]
    T2 --> Q_ENC
    T2 --> ITEM_ENC
    Q_ENC --> SCORE
    ITEM_ENC --> SCORE
  end

  subgraph multimodal["multimodal_v2_ensemble_model (CLIP)"]
    TXT_ENC["CLIP Text Encoder\n(ViT-B/32)"]
    IMG_ENC["CLIP Image Encoder\n(ViT-B/32)"]
    FUSE["Fusion Layer"]
    TXT_ENC --> FUSE
    IMG_ENC --> FUSE
  end

  INPUT_TEXT["Input: Search Query / Item Text"] --> ensemble_ttb_emb
  INPUT_TEXT --> rel_v1
  INPUT_TEXT --> multimodal
  INPUT_IMG["Input: Item Image"] --> multimodal
```

**Model Registry (Azure Blob: `prod_inference_models/`):**

| Model | Type | Output | Use Case |
|-------|------|--------|----------|
| `ensemble_ttb_emb` | Text embedding | float[512] | Query/item embeddings |
| `ensemble_ttb_emb_v2_2` | Text embedding v2 | float[512] | Updated embedding model |
| `ensemble_model_relevance_v1` | Relevance score | float scalar | Ad relevance ranking |
| `universal_r1_ensemble_model_relevance_v1` | Relevance score | float scalar | Universal R1 ranking |
| `search_page_comp_retrieval_ensemble_model_v1` | Retrieval | float[] | Candidate retrieval |
| `item_middle_retrieval_model_tokenizer` | Tokenizer | int[] tokens | Item tokenization |
| `multimodal_v2_ensemble_model` | Multi-modal embedding | float[] | Text + image scoring |

---

## 5. Inter-Service Dependencies

```mermaid
graph TD
  davinci_java["davinci\n(Java gRPC client)\nTritonGrpcClientManager"]
  element_davinci["element-davinci\n(Triton Server)"]
  azure_blob[("Azure Blob Storage\nss-inference-models/")]
  automaton["element-davinci-automaton\n(JMeter perf testing)"]
  element_mlops["Element MLOps Platform\n(ml.prod.walmart.com)\nRolling restart API"]
  concord["Concord\n(post-deploy parallel test hook)"]
  looper["Looper CI/CD\n(container build + deploy)"]

  davinci_java -->|"gRPC Port 8001\n/v2/models/{model}/infer\nBearer token + TLS"| element_davinci
  element_davinci -->|"Load models at startup\nwasbs://ss-inference-models"| azure_blob
  automaton -->|"HTTPS POST perf tests"| element_davinci
  element_mlops -->|"Rolling restart API"| element_davinci
  concord -->|"Post-deploy: trigger automaton tests"| automaton
  looper -->|"Build Docker image\nDeploy to WCNP"| element_davinci
```

---

## 6. Deployment Configuration

**WCNP Deployment (per environment):**

| Environment | GPUs | Replicas | Azure Region | Model Path |
|-------------|------|----------|-------------|------------|
| dev | 1× H100 | 1 | South Central US | `wasbs://ss-inference-models/dev_inference_models` |
| stage | 1× H100 | 1 | South Central US | `wasbs://ss-inference-models/stage_inference_models` |
| prod | 1× H100 | **16** | WestUS2 + SCUS + EastUS2 | `wasbs://ss-inference-models/prod_inference_models` |

**Docker Image:** `docker.prod.walmart.com/midas/sp_triton:1.7`
**Base Image:** `nvidia/tritonserver:24.12-py3`

| Config Key | Value | Description |
|-----------|-------|-------------|
| `TRITON_SERVER_VERSION` | 2.53.0 | Triton version |
| `TRITON_SERVER_GPU_ENABLED` | 1 | GPU enabled |
| `SHM_SIZE` | 5Gi | Shared memory for GPU |
| `TCMALLOC_RELEASE_RATE` | 200 | Memory allocator tuning |
| `TF_AUTOTUNE_THRESHOLD` | 2 | TF tuning |
| Azure secret path | `/Prod/element/.../azure_blobstore_account_key` | Model storage auth |

**Ingress URL pattern:**
`https://ss-davinci-triton-{env}.element.glb.us.walmart.net/seldon/{namespace}/ss-davinci-triton/v2/models/{model}/infer`

---

## 7. Example Scenario — Model Inference for Ad Embedding

```mermaid
sequenceDiagram
  participant DAVINCI_J as davinci (Java)
  participant TRITON as element-davinci (Triton H100)
  participant AZURE as Azure Blob Storage
  participant GPU as NVIDIA H100 GPU

  Note over TRITON,AZURE: At Startup — Model Loading
  TRITON->>AZURE: Load model weights\n(wasbs://ss-inference-models/prod_inference_models/)
  AZURE-->>TRITON: ONNX / TensorRT model files
  TRITON->>GPU: Compile TensorRT engines (JIT on H100)
  Note over TRITON: All models ready for inference

  Note over DAVINCI_J,GPU: Real-time Inference Request
  DAVINCI_J->>TRITON: gRPC ModelInfer\nmodel: ensemble_ttb_emb\ninputs: [{TEXT, BYTES, "Dell Laptop 15\""}]\nAuth: Bearer {tritonAuthToken}

  TRITON->>GPU: Run tokenizer model\n{TEXT → token_ids[512]}
  GPU-->>TRITON: token_ids

  TRITON->>GPU: Run TTB embedding model\n{token_ids → embedding[512]}
  GPU-->>TRITON: float32[512] embedding vector

  TRITON-->>DAVINCI_J: ModelInferResponse\noutput-0: FP32[1, 512]\n[0.024, -0.183, ...]

  DAVINCI_J->>DAVINCI_J: Write to Cassandra L3 + MeghaCache L2 + Caffeine L1
```

---

## 8. Related Repos

| Repo | Role |
|------|------|
| `davinci` (Chapter 15) | Java caching/routing layer that calls this Triton server |
| `element-davinci-automaton` | JMeter-based perf testing suite for this server |
| `element-ss-inference` (Chapter 19) | Predecessor Triton deployment (V100 GPUs, Triton 22.12) |
| `sp-adserver-feast` (Chapter 16) | Online feature store that feeds ML features to models |
