# Learning Notes — NVIDIA Triton-Dynamo Interview Prep

Append-only log of what was actually covered and understood each session. `PLAN.md` is the
forward-looking schedule/reference (read that first); this file is the history — what actually
happened, in what order, and what's genuinely understood vs. still shaky. New entries go at the
bottom, oldest first.

---

## 2026-08-01 — Day 1: model repository + config.pbtxt

**Covered:**
- Model repository directory convention: `<repo>/<model_name>/<version>/<model_file>` +
  `config.pbtxt` at the model level. Why: the directory name *is* the model's API identity (no
  separate name field is authoritative), and this filesystem-scannable layout is what makes
  Triton's polling mode (hot load/unload without restart) possible.
- Version directories are plain integers so Triton can order them for version policy, without
  needing to parse semver or anything semantic.
- `version_policy` (`latest{num_versions}` / `specific{versions}` / `all{}`) controls what's
  *loaded*, not traffic routing — Triton has no built-in canary/weighted-split; that's a client
  or gateway responsibility (client picks a version explicitly per-request).
- Default policy is `latest{num_versions: 1}` — dropping in a new version unloads the old one
  after in-flight requests drain (like connection draining on a load balancer, applied to a
  loaded model instance instead of a TCP connection).
- Hosting multiple models/versions on one GPU is a statistical-multiplexing bet, not free —
  Triton doesn't evict under memory pressure, a load either fits or fails. This is *why*
  disaggregated serving (Dynamo) exists once models are too big to multi-tenant this way.
- `config.pbtxt` core fields: `name` (must match directory), `backend`, `max_batch_size` (implies
  an unstated leading batch dim on every input/output — `dims` in the config excludes it),
  `input`/`output` blocks (name, `data_type` needs the `TYPE_` prefix, `dims`).
- Built and validated a real model end-to-end: `identity_model` using Triton's built-in
  `identity` backend (a Triton-internal test backend — no model file needed, but it enforces
  strict `INPUT<n>`/`OUTPUT<n>` naming, which is backend-specific, not a general Triton rule).
  Sent a real `/v2/models/identity_model/infer` request, got the exact input values echoed back.

**Not yet covered (carried to next session):** dynamic batching mechanics, versioning
hands-on (actually running two versions side by side), OSS issue #8874.

**Environment (see `PLAN.md` for full details/commands):** abandoned the Runpod pod for
single-GPU Triton work — it's itself a container, so no nested Docker, and the bare-metal
`tritonserver` binary needed three separately-chased shared libraries
(`libcudart`, `libssl.so.1.1`, `libdcgm.so.4`). Moved to Harish's local machine (GTX 1660 Ti)
via Podman + NVIDIA Container Toolkit + `nvcr.io/nvidia/tritonserver:26.07-py3`, which just
worked once GPU passthrough was confirmed. This local setup is now the default for single-GPU
days; Runpod's multi-GPU pod is reserved for when Dynamo disaggregation actually needs multiple
GPUs (Day 4-5).

---

## 2026-08-02 — Day 1 continued: dynamic batching + versioning, empirically validated

**Dynamic batching:**
- Concept: batches independent concurrent client requests arriving close together into one
  execute call, to amortize fixed GPU launch/data-movement overhead across samples. Only
  possible because `max_batch_size > 0`. Controlled by `preferred_batch_size` (sizes the batcher
  actively assembles) and `max_queue_delay_microseconds` (ceiling on wait time before executing
  whatever's queued anyway — the throughput/tail-latency tradeoff knob).
- Proved it empirically using the `identity` backend's `execute_delay_ms` parameter (read from
  source: `triton-inference-server/identity_backend/src/identity.cc` — delay is applied *once
  per execute call*, before the per-request loop, which is exactly why batching helps): 4
  concurrent requests with `dynamic_batching{}` enabled completed in **1.017s** total (batched
  into one execute call); with the block removed, same 4 requests took **4.014s** (serialized,
  one instance). Confirmed both runs against actual on-disk config + fresh containers, not
  assumed.
- Side finding: batching merges execution but Triton still demuxes each response back to the
  correct original caller — response order can differ from send order (shell scheduling), but
  content is always correctly per-request.

**Versioning hands-on:**
- Added a second version dir (`identity_model/2/`, empty — `identity` needs no model file).
- With no `version_policy` set (default `latest{num_versions:1}`): only version `2` loaded;
  version `1` was never even attempted (confirmed in server logs + `/v2/models/identity_model`
  showing `"versions":["2"]`).
- Added `version_policy: { all: {} }`: both versions loaded (`"versions":["1","2"]`), and both
  independently addressable via `/v2/models/identity_model/versions/{1,2}/infer` — each returned
  its own `model_version` correctly.

**Workflow note:** Harish drives the Podman container lifecycle himself (has his own
`script.zsh` for restart+test cycles) — I check file contents/API responses when useful but
don't start/stop containers myself, to avoid two of us racing on the same local Docker/Podman
state.

**Day 1 now fully closed**: model repository, config.pbtxt, dynamic batching, versioning all
covered and hands-on validated. Remaining: OSS issue #8874 (carries into this session/Day 2).

**OSS issue #8874 — PR opened:** https://github.com/triton-inference-server/pytorch_backend/pull/203

- Confirmed the bug for real (not just trusted the issue report): read
  `TRITONBACKEND_ModelInstanceReportStatistics`'s actual declaration in
  `triton-inference-server/core`'s `tritonbackend.h` — signature is
  `(instance, request, success, exec_start_ns, compute_start_ns, compute_end_ns, exec_end_ns)`.
  The PT2/AOTI call in `pytorch_backend/src/pt2/model_instance_state.cc` really did pass
  `exec_end_ns` and `compute_start_ns` swapped. Also checked the sibling
  `ReportBatchStatistics` call a few lines down — that one was already correctly ordered, so
  the bug was scoped exactly to what the issue described, nothing broader.
- Interview-relevant point noticed while fixing: all four timestamp args are `uint64_t`, so no
  compiler type-check could ever have caught this argument-order bug — that's *why* it shipped.
  Compiling only guards against syntax errors (caught a missing-comma slip during editing this
  way); the real check was matching against the header by parameter name.
- Repo split worth remembering for the interview: the issue is filed in
  `triton-inference-server/server`, but the actual PT2 backend code lives in the separate
  `triton-inference-server/pytorch_backend` repo. PR was opened there, referencing the issue
  cross-repo (`triton-inference-server/server#8874`) — cross-repo refs don't auto-close on
  merge, only same-repo `Fixes #N` does.
- Ran the repo's actual `pre-commit` hooks (clang-format, codespell, etc.) locally before
  pushing, matching what CI checks — all passed.
- Noticed `rmccormick-instance` in the branch list while forking — almost certainly Ryan
  McCormick's (Tuesday interviewer) own working branch, confirming this repo is squarely in his
  day-to-day area.

---

## 2026-08-02 (cont'd) — Day 2: Python backend

- Compared the Python backend's `pb_utils` API against the raw C++ `identity_backend` source
  (`TRITONBACKEND_ResponseNew` / `TRITONBACKEND_OutputBuffer` / manual input-buffer-chunk
  copying) to motivate *why* the Python backend exists — the raw C++ walkthrough itself didn't
  land well (too much unfamiliar low-level API surface for the time spent), but the contrast
  point came across: Python backend trades a process-boundary/serialization cost for hiding all
  manual buffer/memory-type handling behind `.as_numpy()` / `pb_utils.Tensor(...)`.
- Built `python_add_model` (two inputs, `OUTPUT0 = INPUT0 + INPUT1`) from scratch — correct on
  first submission (`get_input_tensor_by_name(...).as_numpy()`, `np.add`, wrap in
  `pb_utils.Tensor` + `pb_utils.InferenceResponse`, one response per request). Verified live with
  distinct-value + negative-number test cases (not just identical/trivial inputs, which would've
  masked a bug like accidentally returning one input unchanged) — both correct.
- Note for future sessions: heavy raw-C-API walkthroughs (manual buffer pointers, memory-type
  checks) are lower value for this prep than hands-on Python backend + config work — bias toward
  "write and verify" over "read unfamiliar C++ line by line."

**C++ backends:** kept to talking points only (lifecycle hooks mirror what we already read in
`identity.cc`; why real backends are C++ — no process boundary/serialization; backends are
dynamically-loaded `.so`s matched by name; Python backend is *itself* a C++ backend
(`libtriton_python.so`) that spawns a Python stub — not a separate integration point).

**Ensembles:** `platform: "ensemble"`, no `backend` field — scheduler is built into Triton core,
not a loadable backend. Built `sum_ensemble`: a branching DAG (not just linear) — two parallel
`identity_model` passthroughs (one per input) feeding into `python_add_model` for the sum. Real
bug caught during review: `input_map`/`output_map` are protobuf **map fields** — each key→value
pair needs its own `input_map { key: ... value: ... }` block; cramming two pairs into one block
silently drops/overwrites one. Fixed and verified live: `ENS_OUTPUT0` on
`[1,2,3,4]+[10,20,30,40]` came back `[11,22,33,44]` in one round trip.

**`perf_analyzer` (ships in a separate SDK container, `tritonserver:26.07-py3-sdk`, not the main
server image):** re-validated Day 1's dynamic batching finding with the real tool instead of a
`time` wrapper, against `identity_model` (1s `execute_delay_ms`, single instance):
- No `dynamic_batching`, concurrency 4: throughput stuck at **~1 infer/sec**, latency **~4001ms**.
  Server breakdown showed why directly: `queue ~3000ms` + `compute infer ~1000ms` — each request
  waited behind the ones ahead of it. `Execution count` (18) == `Inference count` (18), i.e. no
  batching happened at all.
- Same concurrency 4, `dynamic_batching{preferred_batch_size:[4,8]}` added back: throughput
  **~4 infer/sec**, latency back to **~1001ms** (same as concurrency-1 baseline). `Inference
  count` 72 vs. `Execution count` 18 — exactly 4 inferences per execution, confirming real
  batching.
- Process note: I ran the first `dynamic_batching` comparison against a container that hadn't
  actually been restarted after the config edit — caught it from `Execution count == Inference
  count` in the output (the tell for "no batching happened") before drawing a wrong conclusion,
  but worth double-checking container freshness before trusting a perf number in general.

**Day 2 now fully closed**: Python backend (written + verified), C++ backend concepts (talking
points only — deep line-by-line C++ reading wasn't a good time investment), ensembles (built +
verified a branching DAG), perf_analyzer (validated batching two independent ways).
