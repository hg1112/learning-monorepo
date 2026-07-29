# OSS Contribution Targets — ML Systems / Inference / Serving

Research date: 2026-07-29. Goal: identify open-source repos where Harish (ML Systems/Infra engineer,
background in `apps/triton-rt` — vLLM/SGLang/Triton/Ray/Dynamo — plus `apps/model_serving` and
`apps/finetuning`) can land merged PRs to build a public GitHub profile as an MLE/ML-Sys engineer,
starting from beginner-friendly issues.

**Methodology:** All issue counts and titles below were pulled live via the GitHub REST search API
(`api.github.com/search/issues`) and the GitHub contents/labels APIs using an authenticated `gh api`
session, plus direct fetches of `CONTRIBUTING.md`/equivalent files from `raw.githubusercontent.com`.
Every claim below is cited with the exact query or URL used. Live data was successfully retrieved for
**all 17 candidate repos** — no repo had a failed/empty fetch, though a few required extra digging
(label-name mismatches, symlinked CONTRIBUTING files, an org rename). Those quirks are called out
inline since they're useful signal in their own right (e.g. a stale label or a moved repo tells you
something about repo hygiene).

---

## Shortlist (ranked)

### 1. vllm-project/vllm — highest resume signal, directly matches current work

- **Why:** vLLM is the highest-recognition inference-serving project on this list among people who'd
  hire an ML-Sys engineer, and it's literally in Harish's `apps/triton-rt` stack already. The
  `CONTRIBUTING` doc is unusually good: it has a dedicated "Job Board" section linking directly to
  good-first-issues and a curated "Selected onboarding tasks" project board, and it documents a
  **Python-only dev install** path (`VLLM_USE_PRECOMPILED=1 uv pip install -e .`) that avoids a full
  CUDA/C++ rebuild for non-kernel contributions — a real accessibility win.
  Source: https://raw.githubusercontent.com/vllm-project/vllm/main/docs/contributing/README.md
- **Label / count:** `good first issue`, **28 open**.
  Source: `gh api search/issues?q=repo:vllm-project/vllm+is:issue+is:open+label:"good first issue"` → `total_count: 28`
- **Example open issues:**
  - "[Docs] Document NIXL KV connector metrics aggregation semantics" — https://github.com/vllm-project/vllm/issues/41230
  - "[torch.compile] config hashing refactor follow-ups" — https://github.com/vllm-project/vllm/issues/39479
  - Note: several other GFI-labeled issues (e.g. PTX kernel work, MoE class refactors) are meatier than a typical "first PR" — the label bar here trends higher than in smaller projects, so pick a docs/refactor one first.
- **CONTRIBUTING.md:** exists at `docs/contributing/README.md` (root `CONTRIBUTING.md` is a 3-line
  pointer to it). Very clear: setup, linting (`pre-commit`), testing (`pytest`), PR title taxonomy,
  DCO sign-off requirement, explicit AI-assisted-contribution disclosure policy. One friction point:
  GitHub's PR-limit feature caps non-write contributors at **6 open PRs** at a time.
  Source: https://raw.githubusercontent.com/vllm-project/vllm/main/docs/contributing/README.md

### 2. sgl-project/sglang — matches current work, largest beginner-issue pipeline found

- **Why:** SGLang is Harish's other hands-on serving framework and has the single largest
  "good first issue" open count of any repo checked here. The maintainers explicitly steer new
  contributors toward documentation as an on-ramp before code, which is a low-risk way to land a
  first merged PR fast.
- **Label / count:** `good first issue`, **50 open**.
  Source: `gh api search/issues?q=repo:sgl-project/sglang+is:issue+is:open+label:"good first issue"` → `total_count: 50`
- **Example open issues:**
  - "[Feature] Improve Unit Test Coverage" — https://github.com/sgl-project/sglang/issues/20865
  - "[Feature] Unified JIT / Precompilation Cache Directory" — https://github.com/sgl-project/sglang/issues/19612
  - "model: support new diffusion models" — https://github.com/sgl-project/sglang/issues/27214
- **CONTRIBUTING.md:** the in-repo file (`docs_new/CONTRIBUTING.md`) is a generic Mintlify
  docs-only template, not a real code-contribution guide — a minor repo-hygiene ding. The actual
  guide is hosted at https://docs.sglang.io/developer_guide/contribution_guide.html (linked from
  README). Per that page: new contributors don't have write access (must fork), it explicitly
  recommends **starting with documentation**, and it points to `good first issue`/`help wanted`
  labels as "lower complexity." Build/CUDA requirements for code changes weren't explicit in the
  guide itself.
  Sources: https://raw.githubusercontent.com/sgl-project/sglang/main/docs_new/CONTRIBUTING.md ;
  https://docs.sglang.io/developer_guide/contribution_guide.html

### 3. huggingface/peft — best warm-up repo, cleanest CONTRIBUTING.md found

- **Why:** Small, well-triaged (only 58 open issues total vs. thousands elsewhere), pure Python,
  directly relevant to Harish's `apps/finetuning` work. The CONTRIBUTING guide is the clearest and
  most actionable of everything reviewed here — exact commands, a Makefile (`make test`,
  `make quality`, `make style`), and even a step-by-step checklist for adding a new fine-tuning
  method. Good candidate for a fast first merged PR to prove the workflow before tackling harder repos.
- **Label / count:** `good first issue`, **1 open**.
  Source: `gh api search/issues?q=repo:huggingface/peft+is:issue+is:open+label:"good first issue"` → `total_count: 1`
  — "Comparison of Different Fine-Tuning Techniques for Conversational AI" — https://github.com/huggingface/peft/issues/2310
  (note: this one reads more like an open research/writeup ask than a scoped code task — worth also
  browsing unlabeled `bug`-tagged issues in this repo given how clean the contribution process is).
- **CONTRIBUTING.md:** exists (resolved via a Git symlink to `docs/source/developer_guides/contributing.md`),
  170 lines, pip-installable (`pip install -e ".[test]"`), no compiled/CUDA step required for most contributions.
  Source: https://raw.githubusercontent.com/huggingface/peft/main/docs/source/developer_guides/contributing.md

### 4. ray-project/ray — huge brand recognition, largest raw GFI volume

- **Why:** Ray carries strong resume weight (43k+ stars, used broadly in production ML infra) and its
  Serve/Data/Train components are squarely ML-Sys/infra work. It had the highest absolute count of
  currently-open beginner-labeled issues of anything checked.
- **Label / count:** `good-first-issue` (hyphenated, not "good first issue" — verified via the repo's
  label list), **77 open**.
  Source: `gh api repos/ray-project/ray/labels` (label exists as `good-first-issue`) and
  `gh api search/issues?q=repo:ray-project/ray+is:issue+is:open+label:"good-first-issue"` → `total_count: 77`
- **Example open issues:**
  - "[Serve][LLM] SGLangServer multi-replica support" — https://github.com/ray-project/ray/issues/62480
  - "[data/llm] PrepareMultimodalStage crashes on CPU-only nodes due to vllm.config.ModelConfig GPU detection" — https://github.com/ray-project/ray/issues/64004
  - "[llm] `accelerator_type` is silently ignored when combined with CPU-only configs" — https://github.com/ray-project/ray/issues/62138
- **CONTRIBUTING.md:** exists as `CONTRIBUTING.rst`, but it's a short 49-line stub that mostly links
  out to https://docs.ray.io/en/latest/ray-contribute/getting-involved.html for real detail.
  Per that docs page: labels `good-first-issue` and `contribution-welcome` are the intended
  discovery mechanism, and Python-only contributions and C++ contributions have separate/mixed-complexity
  setup paths (C++ work needs Bazel + clang-format 12; the page doesn't explicitly confirm a
  prebuilt-wheel path for pure-Python edits, so verify this before committing to a Python-only PR).
  Sources: https://raw.githubusercontent.com/ray-project/ray/master/CONTRIBUTING.rst ;
  https://docs.ray.io/en/latest/ray-contribute/getting-involved.html

### 5. huggingface/accelerate — relevant to finetuning/training-systems narrative

- **Why:** Directly adjacent to `apps/finetuning` (distributed launch, FSDP/DeepSpeed config, mixed
  precision) — this is training-systems infra, not generic ML research. Small, curated issue tracker
  (98 open total) and a thorough, welcoming CONTRIBUTING.md.
- **Label / count:** `good first issue`, **1 open**.
  Source: `gh api search/issues?q=repo:huggingface/accelerate+is:issue+is:open+label:"good first issue"` → `total_count: 1`
  — "[Community Contributions] examples on distributed inference using Accelerate" — https://github.com/huggingface/accelerate/issues/3078
  (again, an open-ended community thread rather than one discrete task — treat as a pointer to
  smaller sub-tasks rather than a single PR).
- **CONTRIBUTING.md:** exists, 249 lines, pure Python, standard HF fork/branch/PR flow, no CUDA
  build required for most contributions.
  Source: https://raw.githubusercontent.com/huggingface/accelerate/main/CONTRIBUTING.md

### 6. pytorch/pytorch — top-tier brand, save for after momentum is built

- **Why:** The single most recognizable name on this list to any ML-Sys hiring manager. It also has
  a genuinely active beginner pipeline (66 open GFI issues — second-highest count found), which many
  people assume doesn't exist given the project's scale.
- **Label / count:** `good first issue` (note: also has separate `easy` and `newcomer` labels;
  full label list needed pagination past GitHub's 100-per-page default to find), **66 open**.
  Source: `gh api search/issues?q=repo:pytorch/pytorch+is:issue+is:open+label:"good first issue"` → `total_count: 66`
- **Example open issues:**
  - "[Distributed] Remove duplicate unreachable return in functional collectives fallback" — https://github.com/pytorch/pytorch/issues/191400
  - "[Flight Recorder] Replace raw eval when parsing process-group ranks" — https://github.com/pytorch/pytorch/issues/191399
  - "[Elastic] etcd find_free_port masks socket creation failures" — https://github.com/pytorch/pytorch/issues/191395
  (these read as scoped, Python-level cleanups — plausibly buildable without a full C++/CUDA rebuild,
  but that wasn't independently confirmed per-issue.)
- **CONTRIBUTING.md:** exists, but is enormous (1419 lines) and covers a genuinely heavy native
  build (Bazel/CMake-adjacent build system, ccache/ninja tips, ASAN builds, ~10 sub-guides for
  C++/CUDA/Windows dev). This is the highest build-complexity repo on the shortlist — plan for it
  as a later, higher-effort target rather than a first PR.
  Source: https://raw.githubusercontent.com/pytorch/pytorch/main/CONTRIBUTING.md

### 7. dmlc/xgboost — easiest "get the PR muscle memory" repo, weaker ML-Sys fit

- **Why:** Well-known, respected library (competition-winning brand recognition), extremely
  well-documented contribution process, and the lowest-friction beginner issues found in this whole
  survey (tutorial translation, mypy support, doc updates). Its narrative fit to ML-Sys/inference is
  weaker than the others here — it's classical gradient-boosting, not a serving/training-systems
  project — so treat it as a fast-confidence-building rep rather than a resume centerpiece.
- **Label / count:** `good first issue`, **3 open**.
  Source: `gh api search/issues?q=repo:dmlc/xgboost+is:issue+is:open+label:"good first issue"` → `total_count: 3`
  - "Make all tutorials multi-language" — https://github.com/dmlc/xgboost/issues/11413
  - "Better support for mypy." — https://github.com/dmlc/xgboost/issues/6496
  - "[Doc] Update list of winning solutions in data science competitions using XGBoost" — https://github.com/dmlc/xgboost/issues/6173
- **CONTRIBUTING.md:** no flat `CONTRIBUTING.md`/`.github/CONTRIBUTING.md` found (GitHub's own
  community-profile API confirms `files.contributing = null`), but there's a full Sphinx contributor
  guide under `doc/contrib/` (coding_guide.rst, git_guide.rst, community.rst, ci.rst, docs.rst,
  release.rst, unit_tests.rst — rendered at xgboost.readthedocs.io). Substance is there, just not in
  the conventional single-file location.
  Source: `gh api repos/dmlc/xgboost/contents/doc/contrib` ; https://raw.githubusercontent.com/dmlc/xgboost/master/doc/contrib/index.rst

### 8. ai-dynamo/dynamo — strong narrative fit, thin current pipeline (include with caveat)

- **Why:** This is NVIDIA Dynamo, directly in Harish's `apps/triton-rt` stack and a newer/smaller
  project where a contribution could stand out more than in a saturated repo like PyTorch. The
  CONTRIBUTING.md is a genuinely well-organized "quick links" hub (good-first-issue label link, help-wanted
  label link, Slack/Discord, office hours, design-proposal tracker).
- **Label / count:** `good first issue`, **2 open** (thin right now — check back periodically).
  Source: `gh api search/issues?q=repo:ai-dynamo/dynamo+is:issue+is:open+label:"good first issue"` → `total_count: 2`
  - "[CONTRIBUTION REQUESTED] Add Basetenkenizer as a selectable frontend tokenizer backend" — https://github.com/ai-dynamo/dynamo/issues/12325
  - "[CONTRIBUTION]: [FEATURE]: Enforce user-config preservation in TRT-LLM worker arg_map" — https://github.com/ai-dynamo/dynamo/issues/9288
- **CONTRIBUTING.md:** exists, 44 lines, links out to a hosted doc
  (https://docs.nvidia.com/dynamo/getting-started/contribution-guide) and a repo-local copy at
  `docs/fern/contribution-guide.md` for the full build/PR process.
  Source: https://raw.githubusercontent.com/ai-dynamo/dynamo/main/CONTRIBUTING.md

---

## Cut repos (with reasons)

- **triton-lang/triton** (OpenAI Triton, the GPU kernel compiler — not NVIDIA Triton Inference
  Server): no `good first issue` label; only `help wanted` exists, with **21 open**
  (`gh api search/issues?q=repo:triton-lang/triton+is:issue+is:open+label:"help wanted"` → 21).
  `CONTRIBUTING.md` (71 lines) is a governance/ownership document (who owns which module), not a
  hands-on setup guide (https://raw.githubusercontent.com/triton-lang/triton/main/CONTRIBUTING.md).
  Compiler-internals work (LLVM/MLIR, C++) is high-barrier for a first PR. Worth revisiting later for
  prestige, not as an on-ramp.
- **triton-inference-server/server** (NVIDIA Triton Inference Server — the actual serving-narrative
  match, easy to confuse with the entry above): strong narrative fit but the beginner pipeline is
  essentially empty right now — `good first issue` label exists but only **1 open**
  (`gh api search/issues?q=repo:triton-inference-server/server+is:issue+is:open+label:"good first issue"` → 1).
  CONTRIBUTING.md (127 lines) is fine and describes a direct-PR path for small/doc fixes, but the
  backend is heavy C++. Cut for now due to thin supply, not process quality — reconsider periodically.
- **huggingface/transformers**: despite being the most famous HF repo, `Good First Issue` currently
  has **0 open** (`gh api search/issues?q=repo:huggingface/transformers+is:issue+is:open+label:"Good First Issue"` → 0).
  A separate `contributions-welcome` label has 5 open, but those read as broad/open-ended asks (e.g.
  "Tell Us: What Would Make Trainer Better?") rather than scoped tasks. CONTRIBUTING.md itself is
  solid (228 lines, https://raw.githubusercontent.com/huggingface/transformers/main/CONTRIBUTING.md)
  — the project is just too heavily triaged/competitive for a reliable first-PR entry point right now.
- **huggingface/diffusers**: `good first issue` shows **6 open**
  (`gh api search/issues?q=repo:huggingface/diffusers+is:issue+is:open+label:"good first issue"` → 6),
  but the issue numbers found (6969, 8434, 9329, 9635, 10076) suggest a long-standing, possibly
  picked-over backlog rather than freshly curated tasks. Also weaker fit — diffusion/image generation
  isn't inference-serving/training-systems work. Cut for narrative fit + likely staleness.
- **huggingface/tokenizers**: `good first issue` = **0 open**, `help wanted` = **0 open**, only
  `good second issue` = 2 open (`gh api search/issues?q=repo:huggingface/tokenizers+is:issue+is:open+label:"good first issue"` → 0,
  same query with `help wanted` → 0). Core library is Rust with PyO3 bindings, adding real toolchain
  overhead. Cut for empty beginner pipeline + added language barrier.
- **microsoft/DeepSpeed**: the repo has moved orgs — its actual `full_name` is now
  **`deepspeedai/DeepSpeed`** (confirmed via `gh api repos/microsoft/DeepSpeed` → `full_name: "deepspeedai/DeepSpeed"`);
  searching under the old `microsoft/DeepSpeed` path fails GitHub's search API entirely. Under the
  correct path, `good first issue` = **0 open**
  (`gh api search/issues?q=repo:deepspeedai/DeepSpeed+is:issue+is:open+label:"good first issue"` → 0).
  Strong ML-Sys (training-systems) relevance and a clean CONTRIBUTING.md (152 lines,
  https://raw.githubusercontent.com/deepspeedai/DeepSpeed/master/CONTRIBUTING.md), but cut for now
  due to an empty current beginner pipeline — worth rechecking periodically, and worth noting the org
  rename if referencing this repo anywhere.
- **NVIDIA/TensorRT-LLM**: no `good first issue` label; `help wanted` = **0 open**
  (`gh api search/issues?q=repo:NVIDIA/TensorRT-LLM+is:issue+is:open+label:"help wanted"` → 0). A
  non-standard `Community want to contribute` label has 4 open
  (e.g. "Support cpu inference with scaffolding" — https://github.com/NVIDIA/TensorRT-LLM/issues/3334).
  More importantly, CONTRIBUTING.md (186 lines) requires **every** enhancement/bugfix/change to start
  with an Issue Request that must be reviewed and approved by TensorRT-LLM engineers *before* code
  review even begins (https://raw.githubusercontent.com/NVIDIA/TensorRT-LLM/main/CONTRIBUTING.md) —
  a heavier process gate than any other repo here, stacked on top of a genuinely C++/CUDA-heavy
  runtime. Cut as a first-PR target; revisit once you have a track record.
- **mlc-ai/mlc-llm**: no `CONTRIBUTING.md` found anywhere in the repo (only a `CONTRIBUTORS.md`
  name-list); GitHub's own community-profile API confirms `files.contributing = null`
  (`gh api repos/mlc-ai/mlc-llm/community/profile`). `help wanted` label = **2 open**, and neither
  reads as a scoped coding task (one is a model-quantization request, the other a "model request
  tracking" meta-issue). Repo is **not archived** and had a push as recently as 2026-07-23, but the
  last several commits are submodule-bump/refactor-adaptation commits rather than new feature work
  (`gh api repos/mlc-ai/mlc-llm/commits`), suggesting reduced momentum — plausibly a project whose
  on-device-inference niche has been substantially absorbed by vLLM/SGLang/llama.cpp activity. Not
  formally archived/superseded, but not a confident pick either.
- **microsoft/onnxruntime**: no `good first issue`-style label exists at all in this repo's label
  list; the closest analog, `contributions welcome`, has **48 open**
  (`gh api search/issues?q=repo:microsoft/onnxruntime+is:issue+is:open+label:"contributions welcome"` → 48),
  but sampled examples (WebGPU EP feature gaps, an NPU multi-card race condition, CoreML dynamic-shape
  bugs) skew toward meaty EP/runtime-internals work rather than approachable first tasks. CONTRIBUTING.md
  (100 lines) also requires a feature-request/design-discussion step for non-trivial or public-API-facing
  changes (https://raw.githubusercontent.com/microsoft/onnxruntime/main/CONTRIBUTING.md). Strong
  inference relevance, but cut for this round given the barrier; a good "phase 2" target.

---

## Sequencing recommendation

Optimize for landing one real, merged PR fast to prove the workflow (fork → branch → tests → review →
merge) before spending time on projects with heavy native builds or heavier process gates.

1. **huggingface/peft** and **huggingface/accelerate** — pure Python, small/curated trackers, best
   documented processes found in this survey. Do these first purely to bank a fast, low-risk merged PR
   and learn each org's review cadence.
2. **dmlc/xgboost** — lowest-friction issues found (docs/tooling), good for a second quick win and
   general open-source-etiquette practice, even though its resume narrative is weaker.
3. **ray-project/ray** — bigger infra project, but has a documented (if not 100% confirmed
   Python-wheel-only) path for pure-Python contributions and by far the largest beginner-issue supply
   found. Good bridge between "warm-up" and "serving framework" work.
4. **sgl-project/sglang** then **vllm-project/vllm** — the two repos that most directly match Harish's
   existing `apps/triton-rt` experience and carry the strongest resume signal among the serving
   frameworks. Start with a docs or small refactor issue in each (both explicitly steer newcomers this
   way) before attempting kernel-level GFI issues.
5. **ai-dynamo/dynamo** — keep on a watchlist; check back for new `good first issue` items given its
   strong narrative fit, but don't block on it since supply is currently thin.
6. **pytorch/pytorch** — save for last on this shortlist. Highest brand value, real beginner-issue
   supply (66 open), but by far the heaviest native build of anything here; attempt only once you're
   comfortable with the contribution rhythm from the earlier repos, and pick a GFI issue that reads as
   Python-only/test-only to minimize build pain on the first attempt.

Periodically re-run the same label search against the cut list (`triton-inference-server/server`,
`deepspeedai/DeepSpeed`, `microsoft/onnxruntime`, `NVIDIA/TensorRT-LLM`) — several of these have strong
narrative fit and just happened to have an empty or thin beginner-issue queue on the day of this
research pass (2026-07-29); that can change week to week.
