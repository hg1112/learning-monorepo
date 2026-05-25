# Module 04 — Multi-Agent Orchestration

**Goal:** An orchestrator agent that delegates tasks to specialized subagents and synthesizes results.

---

## Concepts

### Why Multiple Agents?

A single agent with 20 tools and a long system prompt gets confused and makes bad decisions. Specialized agents with 3-5 focused tools each are more reliable and cheaper to run.

Use multi-agent when:
- Tasks are too long for one context window
- Tasks have genuinely parallel subtasks
- You need a critic that is independent of the executor
- Different parts require different capabilities (code vs. research vs. writing)

### Orchestrator Pattern

```
┌───────────────────────────────────────────────────┐
│                  ORCHESTRATOR                     │
│  - Receives high-level task                       │
│  - Plans decomposition                            │
│  - Delegates to subagents                         │
│  - Synthesizes results                            │
└───────────┬───────────────┬───────────────────────┘
            │               │
    ┌───────▼──────┐ ┌──────▼───────┐
    │ Research     │ │ Code         │
    │ Agent        │ │ Agent        │
    │ (search,RAG) │ │ (write,run)  │
    └──────────────┘ └──────────────┘
```

The orchestrator calls subagents as tools:

```python
{
    "name": "call_research_agent",
    "description": "Delegates a research task. Returns a structured report.",
    "input_schema": {
        "type": "object",
        "properties": {
            "task": {"type": "string", "description": "The research question to investigate"},
            "depth": {"type": "string", "enum": ["quick", "thorough"]}
        },
        "required": ["task"]
    }
}
```

### Orchestration Patterns

#### 1. Sequential Pipeline

```
Task → Agent A → result_A → Agent B (uses result_A) → Agent C → Final
```

Use when: each step depends on the previous step's output.
Example: research → outline → write → proofread

#### 2. Parallel Fan-Out

```
Task ──→ Agent A ──→ ┐
      ├→ Agent B ──→ ├─→ Merge → Final
      └→ Agent C ──→ ┘
```

Use when: subtasks are independent and can run concurrently.
Example: research 3 topics simultaneously.

```python
import asyncio

results = await asyncio.gather(
    run_agent(research_agent, "topic A"),
    run_agent(research_agent, "topic B"),
    run_agent(research_agent, "topic C"),
)
```

#### 3. Critic Loop (Self-Improvement)

```
Task → Executor → draft → Critic → feedback → Executor → revised → Critic → ...
```

Loop until critic approves or max iterations hit.
The critic should be a separate agent (or at minimum a separate system prompt) — if it shares context with the executor, it tends to agree with itself.

```python
for _ in range(MAX_REVISIONS):
    draft = executor.run(task, feedback=feedback)
    critique = critic.run(draft)
    if critique.approved:
        break
    feedback = critique.feedback
```

#### 4. Handoff (Routing)

```
User message → Router Agent → picks specialist → Specialist handles it
```

The router classifies intent and hands off to the right agent. No response from router — the specialist replies directly.

### Shared State Between Agents

Agents need a shared scratchpad for coordination:

```python
@dataclass
class SharedState:
    task: str
    subtask_results: dict[str, Any]
    messages: list             # shared conversation history
    memories: MemoryStore      # shared long-term memory
```

Pass this as a parameter — don't use global state.

### Token Budget Per Agent

Track budget at the orchestrator level. Each subagent call costs tokens. Set a per-task budget and abort if exceeded:

```python
if session_tracker.total_cost > MAX_COST_USD:
    raise BudgetExceeded(f"Task aborted: cost ${session_tracker.total_cost:.4f} exceeded budget")
```

---

## What to Build

### `04_multi_agent/demo.py`

Build a **report generation pipeline**:

```
Orchestrator receives: "Write a report on LLM inference optimization techniques"

  → [parallel] Research Agent: searches for techniques, papers
  → [parallel] Code Agent: finds/writes example code snippets
        ↓
  → Writer Agent: synthesizes research + code into a draft report
        ↓
  → Critic Agent: reviews for accuracy, clarity, completeness
        ↓
  → (loop if needed) Writer revises based on critique
        ↓
  → Final structured report
```

Show per-agent token costs in observability output. Show total orchestration cost vs. what a single-agent approach would cost.

---

## Key Takeaways

- Start with one agent — only add agents when you hit clear limits (context, reliability, parallelism)
- The critic must be independent of the executor or it's useless
- Parallel fan-out is the highest ROI pattern — same quality, lower latency
- Always track budget at the orchestrator — subagents don't know how much they've spent collectively
- Shared state is the hardest part of multi-agent systems — keep it minimal and typed

---

## Resources

### Must-Read
- [Building Effective Agents — Anthropic](https://anthropic.com/research/building-effective-agents) — defines the five patterns this module implements: routing, parallelization, orchestrator-workers, evaluator-optimizer
- [Building Agents with the Claude Agent SDK](https://www.anthropic.com/engineering/building-agents-with-the-claude-agent-sdk) — how Anthropic builds multi-agent systems internally
- [How to Build Multi-Agent Systems: Complete 2026 Guide](https://dev.to/eira-wexford/how-to-build-multi-agent-systems-complete-2026-guide-1io6) — covers CrewAI, LangGraph, Google ADK patterns with comparisons

### Papers (skim the intro + results sections)
- [Multi-Agent LLM Orchestration for Incident Response](https://arxiv.org/abs/2511.15755) — real benchmark: multi-agent achieves 100% actionable recommendations vs. 1.7% for single-agent
- [HALO: Hierarchical Autonomous Logic-Oriented Orchestration](https://arxiv.org/html/2601.13671v1) — architecture for hierarchical multi-agent systems

### Frameworks to Know (don't use yet — understand the concepts first)
- [LangGraph](https://www.langchain.com/langgraph) — graph-based multi-agent orchestration; good for complex state machines
- [CrewAI](https://www.crewai.com/) — role-based multi-agent framework; easier to start with
- [Building a Multi-Agent System with LangGraph](https://www.elastic.co/search-labs/blog/multi-agent-system-llm-agents-elasticsearch-langgraph) — hands-on tutorial

### YouTube
- [LangChain channel](https://www.youtube.com/@LangChain) — LangGraph multi-agent tutorials; translates directly to the patterns above
