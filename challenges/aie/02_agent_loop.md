# Module 02 — Single Agent Loop

**Goal:** Build a complete agent that reasons, acts, observes results, and loops until a task is done.

---

## Concepts

### The ReAct Pattern

**Re**ason + **Act** — the foundational pattern for all agents.

```
Thought: I need to find the population of Tokyo.
Action: web_search("Tokyo population 2024")
Observation: "Tokyo population is approximately 13.96 million"
Thought: Now I can answer the question.
Answer: Tokyo's population is about 13.96 million.
```

In practice with tool use, the model's reasoning is implicit in how it selects tools and forms tool inputs. You can make it explicit by asking the model to "think step by step" or use extended thinking.

### Agent Loop Architecture

```
┌──────────────────────────────────────────────┐
│                 AGENT LOOP                   │
│                                              │
│  input ──→ [LLM] ──→ tool_use? ──→ run tool │
│               ↑                       │      │
│               └───────────────────────┘      │
│                                              │
│  stop when: end_turn | max_iterations        │
└──────────────────────────────────────────────┘
```

### Stopping Conditions

An agent must know when to stop. Two mechanisms:

1. **`stop_reason == "end_turn"`** — the model decided it's done (no more tool calls needed)
2. **Max iterations guard** — always set a ceiling (e.g. 10 loops) to prevent runaway agents and cost explosions

```python
MAX_ITERATIONS = 10

for i in range(MAX_ITERATIONS):
    response = call_model(messages)
    if response.stop_reason == "end_turn":
        break
    # handle tool call...
else:
    # hit max iterations — return partial result or raise
```

### Structured Output

When an agent produces a final answer, return it as structured data (not raw prose) so downstream code can use it reliably.

Two approaches:

**A) JSON in system prompt + parsing**
```python
system = "Always return your final answer as JSON: {\"answer\": ..., \"sources\": [...]}"
result = json.loads(extract_text(response))
```

**B) Force a tool call as the "done" signal**
```python
done_tool = {
    "name": "finish",
    "description": "Call this when you have a final answer.",
    "input_schema": {
        "type": "object",
        "properties": {
            "answer": {"type": "string"},
            "confidence": {"type": "number"}
        }
    }
}
```
When the model calls `finish`, the loop exits and you return `tool_call.input`.

### System Prompt Design for Agents

A well-designed agent system prompt has four parts:

```
1. ROLE        — who the agent is, what it's responsible for
2. TOOLS       — brief description of available tools and when to use each
3. PROCESS     — how to approach tasks (think before acting, verify results)
4. OUTPUT      — format of the final answer
```

Keep it under ~500 tokens where possible. Cache it.

---

## What to Build

### `02_agent_loop/demo.py`

Build a **research agent** that can:
- Search the web (mocked)
- Read files
- Write a summary report

Demo task: "Research the top 3 use cases for vector databases and write a summary."

The agent should:
1. Break the task into searches
2. Run multiple tool calls
3. Synthesize results
4. Return a structured final report

Show the full loop in the terminal output — each iteration, which tool was called, and the final answer. The observability layer should show cumulative cost growing with each loop iteration.

---

## Key Takeaways

- An agent is just a loop around tool use — the complexity is in the tools and the prompt
- Always add a max iterations guard before anything else
- The "finish" tool pattern gives you clean structured output without prompt hacks
- Watch your context window — long tool results fill it up fast; truncate large results before appending

---

## Resources

### Must-Read
- [Building Effective Agents — Anthropic](https://anthropic.com/research/building-effective-agents) — the five canonical workflow patterns; orchestrator-workers is the ReAct pattern at scale
- [ReAct Pattern: Interleaving Reasoning and Action](https://mbrenndoerfer.com/writing/react-pattern-llm-reasoning-action-agents) — interactive walkthrough of the thought-action-observation loop
- [What is a ReAct Agent? — IBM](https://www.ibm.com/think/topics/react-agent) — clean conceptual explainer

### Tutorials
- [Implementing ReAct From Scratch](https://www.dailydoseofds.com/ai-agents-crash-course-part-10-with-implementation/) — no frameworks, pure Python; shows every step of the loop
- [LangChain ReAct Agent Pattern Explained](https://langchain-tutorials.github.io/langchain-react-agent-pattern-2026/) — useful for seeing how frameworks handle what you're building manually

### Courses
- [Agentic AI — Andrew Ng / DeepLearning.AI](https://learn.deeplearning.ai/courses/agentic-ai) — covers reflection, tool use, planning, and multi-agent collaboration (4 patterns, ~2 hours, free)

### YouTube
- [Cole Medin](https://www.youtube.com/@ColeMedin) — production agent loop implementations with real tools
