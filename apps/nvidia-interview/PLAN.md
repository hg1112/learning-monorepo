# NVIDIA Triton-Dynamo Team — Interview Prep

Referral: Mudit Agarwal. Recruiter: Paul Velasquez (pvelasquez@nvidia.com). Role: Senior MLE.

## Interview logistics

Remote onsite, video (Microsoft Teams), camera+mic on for the full loop. No outside AI tools
during the interview itself (this prep happens beforehand, not during).

| Day | Time (PT) | Interviewer | Likely focus (see below) |
|---|---|---|---|
| Tue Aug 11, 2026 | 1:00–1:45pm | David Zier | Vision / leadership / architecture fit |
| Tue Aug 11, 2026 | 2:00–2:45pm | Ryan McCormick | Triton internals, distributed systems, possibly coding |
| Tue Aug 11, 2026 | 3:00–3:45pm | Yingge He | Unknown — no public footprint found, treat as general |
| Wed Aug 12, 2026 | 12:00–12:45pm | J Wyman | Systems/architecture design |
| Wed Aug 12, 2026 | 1:00–1:45pm | Tanmay Verma | Dynamo internals — disaggregated serving, KV-cache routing, NIXL/UCX |
| Wed Aug 12, 2026 | 2:00–2:30pm | Paul Velasquez | Recruiter chat |
| Wed Aug 12, 2026 | 2:45–3:00pm | Sushant Deepak Ochani | Insider volunteer chat |

**Panel research (2026-07-30):**
- **David Zier** — Director, DL System Software at NVIDIA; his org owns Triton Inference Server,
  GenAI-Perf, and Dynamo. PhD Comp. Eng (Oregon State), joined NVIDIA 2009 as CPU architect,
  moved into AI/DL systems leadership in 2018.
- **Ryan McCormick** — Senior SWE on Triton Inference Server; ML/systems/distributed-systems
  focus. Co-presented "Triton + Ray Serve" at Ray Summit 2024.
- **J Wyman** — Senior system software architect, Triton / next-gen NVIDIA inference serving.
- **Tanmay Verma** — Core Dynamo engineer (GitHub `tanmayv25`), shipped Dynamo 1.0, active
  committer on disaggregated serving (TRT-LLM/UCX) and KV-cache routing. Also contributes to
  `triton-inference-server/server`.
- **Yingge He** — no public profile found.

**Implication:** weight prep toward Dynamo internals (disaggregated prefill/decode, KV-cache
routing, NIXL) ≈ Triton internals (backends, batching, versioning) > systems/architecture design
> behavioral/leadership > general ML fundamentals. Coding is likely systems-style (write a
batching scheduler / KV cache / router), not LeetCode.

## Retention baseline

Treat prior exposure (June 2026 Luma prep, `apps/triton-rt` lessons 0001-0005) as **not
retained** — confirmed pattern from that workspace (see `feedback_verify_prior_knowledge`
memory). Relearn from scratch, don't assume.

## Working agreements

- Step-by-step teaching with reasons, not fire-and-forget code generation. Let Harish write the
  code; review, don't rewrite.
- Shell/practice commands: give as a code block for Harish to run and paste back output. File
  edits/writes in this repo are fine to do directly.
- Runpod: multi-GPU pod available — use it for real (not just conceptual) disaggregated Dynamo
  work.
- Optimize for interview fluency over polish — code in `runpod/` doesn't need to be shareable.

## Local environment (established Day 1, 2026-08-01)

Single-GPU Triton work (model repository, config.pbtxt, backends, dynamic batching, versioning)
runs on Harish's **local machine** (GTX 1660 Ti, driver 595.71.05, CUDA 13.2), not the Runpod
pod — the pod is itself a container and doesn't support nested Docker, which made a bare-metal
Triton install painful (chased missing `libcudart.so.13`, `libssl.so.1.1`, `libdcgm.so.4` one at
a time before abandoning that path). Locally, Docker wasn't available either (aliases to Podman
on this Ubuntu version) — Podman works fine instead:

```bash
podman run --rm --gpus all \
  -p 8000:8000 -p 8001:8001 -p 8002:8002 \
  -v /home/karna/Desktop/learning-monorepo/apps/nvidia-interview/model_repository:/models \
  nvcr.io/nvidia/tritonserver:26.07-py3 \
  tritonserver --model-repository=/models
```

Gotchas hit once, don't need to re-debug:
- Podman needs fully-qualified image names (`docker.io/nvidia/...`, not `nvidia/...`) — no
  default search registries configured.
- Rootless Podman's port forwarding doesn't proxy `::1` (IPv6) — `curl` tries IPv6 first by
  default and gets "connection reset by peer". Use `curl -4` or `curl 127.0.0.1:...`.
- `nvcr.io/nvidia/tritonserver:26.07-py3` matches driver's CUDA 13.2 and is confirmed working
  end-to-end with GPU passthrough.

Reserve the Runpod multi-GPU pod for when real multi-GPU is actually needed (Dynamo disaggregated
serving, Day 4-5) — not for single-GPU Triton fundamentals.

Test model used for hands-on: `identity_model` in `model_repository/`, using Triton's built-in
`identity` backend (no model file needed, just `config.pbtxt`) — chosen specifically to avoid
needing an ONNX/PyTorch export toolchain while learning repo structure/config. Note: `identity`
is one of Triton's own internal test backends, so it enforces input/output names of the form
`INPUT<n>`/`OUTPUT<n>` — a quirk of this backend, not a general Triton rule.

## Day-by-day (today = Jul 30, interview = Aug 11-12)

| Day | Date | Focus |
|---|---|---|
| 1 | Jul 30 (actually done 2026-08-01) | Triton internals from scratch: model repository ✅, `config.pbtxt` ✅ (validated end-to-end with a running server + real inference request). Still open: dynamic batching, versioning hands-on (multiple versions loaded side by side), OSS issue #8874 — pick up next session. |
| 2 | Jul 31 (actually done 2026-08-02/03) | Triton backends (Python/C++) ✅, ensembles ✅, `perf_analyzer` ✅ — all built and hands-on validated. #8874 PR already opened Day 1. |
| 3 | Aug 1 | Dynamo internals: disaggregated prefill/decode, planner architecture. Start OSS issue #12296. |
| 4 | Aug 2 | Dynamo internals: KV-cache routing, NIXL/UCX transport. |
| 5 | Aug 3 | Runpod hands-on: real multi-GPU disaggregated serving run with Dynamo. |
| 6 | Aug 4 | vLLM/SGLang refresh: PagedAttention, continuous batching, RadixAttention. |
| 7 | Aug 5 | Systems coding practice: KV cache w/ eviction, batching scheduler, KV-aware router. |
| 8 | Aug 6 | System design practice: multi-tenant inference platform, disaggregation trade-offs, SLO design. |
| 9 | Aug 7 | GPU/CUDA fundamentals refresh (architecture, memory hierarchy) — J Wyman's likely area. |
| 10 | Aug 8 | Behavioral/leadership prep (Senior-MLE STAR stories, why NVIDIA/this team). Wrap up #12296. |
| 11 | Aug 9 | Full mock interview: Triton Q&A + Dynamo Q&A + 1 coding + 1 system design, timed. |
| 12 | Aug 10 | Light review only on mock weak spots. No new material. |

## OSS contribution track

Tracked in `experiments/open-source/weekly_candidates.md` (2026-07-30 entry). Picks:

1. **[triton-inference-server/server#8874](https://github.com/triton-inference-server/server/issues/8874)** —
   PT2/AOTI PyTorch backend swaps two timestamp args, corrupting latency stats. ✅ **PR opened
   2026-08-02**: [triton-inference-server/pytorch_backend#203](https://github.com/triton-inference-server/pytorch_backend/pull/203).
   Fix lives in the separate `pytorch_backend` repo, not `server` — cross-repo issue ref, won't
   auto-close. Watch for review/CI feedback.
2. **[ai-dynamo/dynamo#12296](https://github.com/ai-dynamo/dynamo/issues/12296)** — opt-in
   graceful shutdown hooks for the `dynamo_worker` Python decorator. Maintainer sketched the
   API shape. Python-facing, not core Rust.

Even unmerged, an open PR against the team's own repos is real signal — mention it regardless
of merge status.

## Runpod projects

- `runpod/triton-backend/` — stand up Triton on the pod, custom Python backend for a small real
  model, dynamic batching + an ensemble (preprocess → model → postprocess), load test with
  `perf_analyzer`.
- `runpod/dynamo-disagg/` — on the multi-GPU pod, split prefill/decode across GPUs with Dynamo,
  observe KV-cache transfer, reason about when disaggregation helps vs. hurts.
