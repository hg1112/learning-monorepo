# Module 05 — Production Patterns

**Goal:** Make agents reliable, measurable, and cost-efficient at scale.

---

## Concepts

### Evals (The Most Important Thing You're Not Doing)

An **eval** is a test suite for AI behavior. It's how you know if a change made things better or worse.

Without evals:
- You're guessing whether your prompt changes helped
- You ship regressions you don't notice until users complain
- Debugging is "try it and see"

A minimal eval:

```python
TEST_CASES = [
    {"input": "What is 2+2?",          "expected": "4"},
    {"input": "Summarize X",           "expected_contains": ["key point 1", "key point 2"]},
    {"input": "Classify this review",  "expected_label": "positive"},
]

for case in TEST_CASES:
    result = agent.run(case["input"])
    score = evaluate(result, case)
    print(f"{score:.0%} — {case['input'][:40]}")
```

**Scoring strategies:**
- **Exact match** — for classification, structured fields
- **LLM-as-judge** — ask a model to score quality (most flexible)
- **Contains** — output includes required phrases/facts
- **Regex** — for structured formats

Run evals before and after every significant prompt or code change.

### Cost Budgeting

```python
@dataclass
class Budget:
    max_usd: float
    max_input_tokens: int
    max_output_tokens: int
    
    def check(self, tracker: ObservabilityTracker):
        if tracker.total_cost > self.max_usd:
            raise BudgetExceeded(f"${tracker.total_cost:.4f} > ${self.max_usd}")
```

Set budgets at three levels:
1. **Per call** — catch runaway single requests
2. **Per task** — catch agents that loop too long
3. **Per session/day** — catch billing surprises

### Retries + Exponential Backoff

The API will occasionally return rate limit errors (429) or transient failures (529). Always retry with backoff:

```python
import time

def call_with_retry(fn, max_retries=3):
    for attempt in range(max_retries):
        try:
            return fn()
        except anthropic.RateLimitError:
            wait = 2 ** attempt          # 1s, 2s, 4s
            time.sleep(wait)
    raise RuntimeError("Max retries exceeded")
```

### Context Window Management at Scale

When running long tasks, context fills up. Strategies:

| Strategy | When to use |
|----------|-------------|
| **Summarize old turns** | Conversational agents with many turns |
| **Sliding window** | Simple agents, drop oldest N turns |
| **RAG injection** | Large knowledge bases — retrieve per turn |
| **Fresh context per subtask** | Multi-agent — each subagent gets clean context |

Track `input_tokens` per call — alert when approaching 80% of the window limit.

### Prompt Versioning

Treat prompts like code:

```
prompts/
  v1_system_prompt.txt
  v2_system_prompt.txt   ← current
  CHANGELOG.md           ← what changed, why, eval results
```

Never edit a prompt in place without recording the version. Eval results should be attached to each version.

### Caching at Scale

Prompt caching is only active for 5 minutes (ephemeral). For longer-lived cache:
- **Batch similar requests** — process many requests with the same system prompt together
- **Request deduplication** — identical inputs get cached responses
- **Static prefix maximization** — put stable content (instructions, docs) before dynamic content (user input) so the cache hit rate is high

---

## What to Build

### `05_production/demo.py`

1. **Eval harness** — 10 test cases, LLM-as-judge scoring, pass/fail report
2. **Budget-aware agent** — runs a task, aborts if cost exceeds $0.10, reports final cost
3. **Retry wrapper** — simulate rate limit errors, show backoff working
4. **Context manager** — agent that summarizes old turns when context hits 80% of limit

### Observability Dashboard

Extend the shared observability module to write a JSON log file:

```json
{
  "session_id": "abc123",
  "calls": [
    {"timestamp": "...", "input_tokens": 412, "output_tokens": 89, "cost": 0.00258, "latency_ms": 234},
    ...
  ],
  "totals": {"calls": 5, "cost": 0.0412, "input_tokens": 2100, "output_tokens": 445}
}
```

Then a `report.py` that reads these logs and prints trends: cost per session, avg latency, cache hit rate.

---

## Key Takeaways

- Evals are the difference between engineering and vibes — build them first, not last
- Every agent needs a cost ceiling before going near production
- Prompt caching is free money — always cache system prompts
- Log everything: you can't optimize what you can't measure

---

## Resources

### Observability Platforms
- [Langfuse — Token & Cost Tracking](https://langfuse.com/docs/observability/features/token-and-cost-tracking) — open-source, self-hostable, tracks cache tokens, supports all Anthropic models natively
- [LangSmith Observability](https://www.langchain.com/langsmith/observability) — per-trace cost, latency P50/P99, error rate dashboards
- [Langfuse vs LangSmith vs Helicone — TCO Comparison](https://www.digitalapplied.com/blog/observability-stack-tco-calculator-langsmith-langfuse-helicone) — choose based on scale and budget
- [Best LLM Observability Tools 2026](https://www.firecrawl.dev/blog/best-llm-observability-tools) — landscape overview

### Evals
- [Context Engineering Best Practices — Comet](https://www.comet.com/site/blog/context-engineering/) — covers eval design as part of context engineering
- [LLM Observability Explained (Langfuse, LangSmith, LangWatch)](https://www.langflow.org/blog/llm-observability-explained-feat-langfuse-langsmith-and-langwatch) — how observability ties into eval workflows

### Production Architecture
- [The LLM Context Problem in 2026](https://blog.logrocket.com/llm-context-problem-strategies-2026/) — context rot, sliding window strategies, production-scale context management
- [Build Production AI Agents with Claude Agent SDK](https://letsdatascience.com/blog/claude-agent-sdk-tutorial) — production patterns using the Agent SDK
