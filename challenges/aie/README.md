# AI Engineering Study Plan

Progressive curriculum: basic agents → complex orchestrations → harness engineering.

## Modules

| # | Module | Key Concepts |
|---|--------|-------------|
| [00](./00_sdk_basics.md) | SDK Basics + Observability | API calls, streaming, token/cost tracking, prompt caching |
| [01](./01_tools.md) | Skills / Tools | Tool schemas, tool call loop, multi-tool, error handling |
| [02](./02_agent_loop.md) | Single Agent Loop | ReAct pattern, stopping conditions, structured output |
| [03](./03_memory.md) | Memory Layers | Working / short-term / long-term / semantic memory |
| [04](./04_multi_agent.md) | Multi-Agent Orchestration | Orchestrator, subagents, fan-out, critic loop, handoffs |
| [05](./05_production.md) | Production Patterns | Evals, cost budgeting, retries, observability at scale |
| [06](./06_harness.md) | Harness Engineering | Pi + Claude Code — skills, extensions, context engineering |

## Cross-Ecosystem Concepts

These concepts appear in every major AI SDK and framework. Learn once, apply everywhere.

| Concept | Anthropic | OpenAI | Gemini |
|---------|-----------|--------|--------|
| Tool calling | `tools` + `tool_use` block | `tools` + `function` call | `function_declarations` |
| System prompt | `system` param | `messages[role=system]` | `system_instruction` |
| Streaming | `.stream()` | `stream=True` | `stream=True` |
| Structured output | JSON in prompt + parse | `response_format` | `response_schema` |
| Token usage | `response.usage` | `response.usage` | `response.usage_metadata` |
| Caching | `cache_control` blocks | Prompt caching (beta) | Context caching |

## Stack

- Python 3.12
- `anthropic` SDK (primary — concepts transfer to OpenAI/Gemini)
- `numpy` (semantic memory — cosine similarity, no heavy vector DB needed early on)
- `sqlite3` (stdlib — long-term memory store)
- Pi harness (`pi.dev`) — for harness-level skills and extensions
- Claude Code — hooks, skills, MCP servers

---

## Essential Reading (Start Here)

| Resource | Why |
|----------|-----|
| [Building Effective Agents — Anthropic](https://anthropic.com/research/building-effective-agents) | The canonical reference. Five workflow patterns (chaining, routing, parallelization, orchestrator-workers, evaluator-optimizer). Read before Module 04. |
| [Claude Agent SDK Overview](https://code.claude.com/docs/en/agent-sdk/overview) | The SDK powering Claude Code, now available as a general-purpose agent runtime. |
| [Building Agents with the Claude Agent SDK](https://www.anthropic.com/engineering/building-agents-with-the-claude-agent-sdk) | Anthropic engineering blog — practical walkthrough of the SDK. |
| [anthropic-sdk-python on GitHub](https://github.com/anthropics/anthropic-sdk-python) | Source code + examples. |
| [claude-agent-sdk-python on GitHub](https://github.com/anthropics/claude-agent-sdk-python) | Agent SDK source. |

## Courses

| Course | Level | Cost |
|--------|-------|------|
| [Agentic AI — Andrew Ng / DeepLearning.AI](https://learn.deeplearning.ai/courses/agentic-ai) | Beginner | Free |
| [RAG — DeepLearning.AI](https://learn.deeplearning.ai/courses/retrieval-augmented-generation) | Beginner | Free |
| [LLM Engineering, RAG & AI Agents Masterclass — Udemy](https://www.udemy.com/course/become-an-llm-agentic-ai-engineer-14-day-bootcamp-2025/) | Beginner–Intermediate | Paid |

## YouTube Channels

| Channel | Best For |
|---------|---------|
| [Andrej Karpathy](https://www.youtube.com/@AndrejKarpathy) | LLM internals from first principles |
| [Cole Medin](https://www.youtube.com/@ColeMedin) | Production AI agents, practical builds |
| [LangChain](https://www.youtube.com/@LangChain) | Agent frameworks, LangGraph patterns |
| [AssemblyAI](https://www.youtube.com/@AssemblyAI) | Tool use, evals, LLM app tutorials |
| [3Blue1Brown](https://www.youtube.com/@3blue1brown) | Transformer math and intuition |

## Context Engineering Deep Dives

- [Context Engineering: How to Build Reliable LLM Systems](https://www.contextstudios.ai/blog/context-engineering-how-to-build-reliable-llm-systems-by-designing-the-context)
- [The LLM Context Problem in 2026: Memory, Relevance, Scale](https://blog.logrocket.com/llm-context-problem-strategies-2026/)
- [Complete Guide to Context Engineering for Coding Agents](https://latitude-blog.ghost.io/blog/context-engineering-guide-coding-agents/)
- [ByteByteGo: A Guide to Context Engineering for LLMs](https://blog.bytebytego.com/p/a-guide-to-context-engineering-for)
