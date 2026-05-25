# Module 00 — SDK Basics + Observability

**Goal:** Make your first API call and immediately see token/cost/latency data after every call.

Build the observability layer first — it wires into every subsequent module.

---

## Concepts

### The Message Loop

Every SDK works the same way: you send an array of messages, the model appends its reply.

```
[system]     → sets the agent's persona, constraints, background knowledge
[user]       → the human's input
[assistant]  → the model's reply (added back to messages for multi-turn)
[user]       → next turn
...
```

The **context window** is the total size of this array in tokens. When it fills up, old messages must be summarized or dropped.

### Token Counting + Cost

Every API response includes a `usage` object:

```python
response.usage.input_tokens    # tokens you sent
response.usage.output_tokens   # tokens the model generated
response.usage.cache_read_input_tokens   # tokens served from cache (cheap)
response.usage.cache_creation_input_tokens  # tokens written to cache
```

**claude-sonnet-4-6 pricing (per 1M tokens):**

| Token type | Price |
|-----------|-------|
| Input | $3.00 |
| Output | $15.00 |
| Cache write | $3.75 |
| Cache read | $0.30 |

### Prompt Caching

If your system prompt is large (instructions, docs, examples), prefix it with `cache_control: {type: "ephemeral"}`. The first call writes it to cache; subsequent calls within 5 minutes pay only $0.30/1M instead of $3.00/1M — a 10x cost reduction.

```python
system=[{
    "type": "text",
    "text": "...your long system prompt...",
    "cache_control": {"type": "ephemeral"}
}]
```

### Streaming

Streaming returns tokens as they're generated rather than waiting for the full response. Use it for any user-facing output — makes the experience feel instant.

```python
with client.messages.stream(...) as stream:
    for text in stream.text_stream:
        print(text, end="", flush=True)
```

---

## What to Build

### `shared/observability.py`

A decorator that wraps any API call and prints:

```
┌─────────────────────────────────────────┐
│ Call #1 — 234ms                         │
│ Input: 412 tokens  ($0.00124)           │
│ Output: 89 tokens  ($0.00134)           │
│ Cache read: 0 | Cache write: 0          │
│ Call total: $0.00258                    │
├─────────────────────────────────────────┤
│ Session total: $0.00258 | 3 calls       │
└─────────────────────────────────────────┘
```

Design it as a context manager so any module can import and wrap calls:

```python
with ObservabilityTracker() as tracker:
    response = client.messages.create(...)
    tracker.record(response)
```

### `00_sdk_basics/demo.py`

1. Basic completion with observability output
2. Multi-turn conversation (show context building up)
3. Streaming response
4. Same call with/without caching — show cost difference

---

## Key Takeaways

- Every SDK call is stateless — **you** maintain the messages list between turns
- The model has no memory except what's in the context window
- Caching is the single highest-ROI optimization for repeated system prompts
- Always wire observability before building anything else — flying blind is expensive

---

## Resources

### Official Docs
- [Anthropic Python SDK — GitHub](https://github.com/anthropics/anthropic-sdk-python) — source, examples, changelog
- [Claude Agent SDK Overview](https://code.claude.com/docs/en/agent-sdk/overview) — higher-level agent runtime built on the same primitives
- [Anthropic API Docs — Messages](https://docs.anthropic.com/en/api/messages) — full reference for the messages endpoint
- [Prompt Caching Guide](https://docs.anthropic.com/en/docs/build-with-claude/prompt-caching) — how `cache_control` works, TTLs, eligible positions

### Observability Tools (use when moving beyond your own tracker)
- [Langfuse — Token & Cost Tracking](https://langfuse.com/docs/observability/features/token-and-cost-tracking) — open-source, supports Anthropic natively, tracks cached tokens
- [LangSmith — Cost Tracking](https://docs.langchain.com/langsmith/cost-tracking) — LangChain's observability platform; configurable per-model pricing
- [Best LLM Observability Tools 2026](https://www.firecrawl.dev/blog/best-llm-observability-tools) — comparison of Langfuse, LangSmith, Helicone, Phoenix

### YouTube
- [RAG Explained For Beginners](https://www.youtube.com/watch?v=_HQ2H_0Ayy0) — foundational context for why you need to manage what goes in the window
