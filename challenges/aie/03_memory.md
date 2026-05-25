# Module 03 — Memory Layers

**Goal:** Agents that remember things — within a session, across sessions, and by semantic similarity.

---

## Concepts

### The Four Memory Layers

```
┌─────────────────────────────────────────────────────────┐
│  LAYER 1: Working Memory (context window)               │
│  Scope: current conversation                            │
│  Storage: messages[] array in RAM                       │
│  Limit: ~200K tokens for claude-sonnet-4-6              │
├─────────────────────────────────────────────────────────┤
│  LAYER 2: Short-Term Memory (in-process)                │
│  Scope: current session (process lifetime)              │
│  Storage: Python dict / dataclass                       │
│  Use: user preferences, task state, intermediate results│
├─────────────────────────────────────────────────────────┤
│  LAYER 3: Long-Term Memory (persistent)                 │
│  Scope: across sessions                                 │
│  Storage: SQLite / JSON file                            │
│  Use: user profile, learned facts, conversation history │
├─────────────────────────────────────────────────────────┤
│  LAYER 4: Semantic Memory (vector search)               │
│  Scope: large document/knowledge collections            │
│  Storage: embeddings + cosine similarity (numpy)        │
│  Use: RAG — retrieve relevant docs and inject to context│
└─────────────────────────────────────────────────────────┘
```

### Layer 1: Working Memory (Context Window Management)

The context window is finite. Strategies when it fills up:

**Summarization** — compress old turns into a summary, keep recent turns verbatim:
```python
if token_count(messages) > THRESHOLD:
    old_turns = messages[:-KEEP_RECENT]
    summary = summarize(old_turns)          # one LLM call
    messages = [summary_message] + messages[-KEEP_RECENT:]
```

**Sliding window** — drop oldest turns, always keep system prompt + recent N turns.

**RAG injection** — don't keep everything in context; retrieve only what's relevant per turn.

### Layer 2: Short-Term Memory

A dict that the agent reads/writes via tools:

```python
memory_store = {}

def remember(key: str, value: str):
    memory_store[key] = value

def recall(key: str) -> str:
    return memory_store.get(key, "not found")
```

Give these as tools. The model decides what to save and when to look things up.

### Layer 3: Long-Term Memory (SQLite)

```python
# Schema: (id, key, value, timestamp, tags)
# Agent tools: save_memory(key, value), search_memory(query), list_memories()
```

The agent uses `save_memory` to persist facts across sessions.
On startup, inject relevant long-term memories into the system prompt.

### Layer 4: Semantic Memory (RAG)

**Retrieval Augmented Generation** — the most important pattern in production AI systems.

```
Documents → Embed → Store (numpy array of vectors)
                              ↓
Query → Embed → Cosine similarity → Top-K docs → Inject to context → Answer
```

```python
# Embed with Anthropic's embedding API or use a small local model
# Cosine similarity with numpy — no vector DB needed for <100K docs
def search(query: str, k: int = 3) -> list[str]:
    query_vec = embed(query)
    scores = cosine_similarity(query_vec, doc_vectors)
    top_k = np.argsort(scores)[-k:][::-1]
    return [docs[i] for i in top_k]
```

**Chunking strategy matters:**
- Too small (< 100 tokens): loses context
- Too large (> 500 tokens): dilutes relevance
- Overlap chunks by ~20% to avoid splitting concepts at boundaries

---

## What to Build

### `03_memory/demo.py`

Build a **personal assistant agent** with all four memory layers:

1. **Working memory** — multi-turn conversation, show context window token count each turn
2. **Short-term memory** — agent remembers your name/preferences mid-session via tools
3. **Long-term memory** — save a fact, kill the process, restart, agent recalls it
4. **Semantic memory** — load 10 markdown docs, ask a question, agent retrieves the relevant one

Demo flow:
```
User: "My name is Harish and I prefer Python over Java"
Agent: [calls remember("name", "Harish"), remember("lang_pref", "Python")]

User: "What's my name?"
Agent: [calls recall("name")] → "Your name is Harish."

# restart process
User: "What's my preferred language?"
Agent: [calls search_long_term_memory("language preference")] → "Python"
```

---

## Key Takeaways

- Most production agents fail because of **context management** — not model capability
- Long-term memory requires the agent to decide what's worth saving (give it a "save" tool, not just auto-save everything)
- RAG relevance is highly sensitive to chunking and embedding quality — test it with adversarial queries
- Inject long-term memories into the system prompt, not the user message — it's cheaper with caching

---

## Resources

### RAG — YouTube
- [RAG Explained For Beginners](https://www.youtube.com/watch?v=_HQ2H_0Ayy0) — best starting point, hands-on project included
- [RAG Explained in 20 Minutes](https://www.youtube.com/watch?v=RosLeHGBLoY) — quick overview with a live demo
- [Learn RAG From Scratch — LangChain Engineer](https://www.youtube.com/watch?v=sVcwVQRHIc8) — build the full pipeline: chunk → embed → retrieve → generate
- [Complete RAG Tutorial 2025 Playlist](https://www.youtube.com/playlist?list=PLNIQLFWpQMRUMjxfe8o6g3uzJ6LH_VotY) — multi-part series

### RAG — Courses & Docs
- [Retrieval Augmented Generation — DeepLearning.AI](https://learn.deeplearning.ai/courses/retrieval-augmented-generation) — short course, covers design, implementation, vector DBs
- [SingleStore: Beginner's Guide to RAG](https://www.singlestore.com/blog/a-guide-to-retrieval-augmented-generation-rag/) — clean written guide with diagrams

### Agentic Memory Architecture
- [Memory Architecture for Agentic Systems — GitHub Gist](https://gist.github.com/spikelab/7551c6368e23caa06a4056350f6b2db3) — concise taxonomy with code sketches
- [Episodic Memory for AI Agents](https://atlan.com/know/episodic-memory-ai-agents/) — explains the episodic/semantic/procedural distinction
- [Agent Memory: Why Your AI Has Amnesia](https://blogs.oracle.com/developers/agent-memory-why-your-ai-has-amnesia-and-how-to-fix-it) — practical diagnosis and fix patterns
- [How to Build Memory-Powered Agentic AI](https://www.marktechpost.com/2025/11/15/how-to-build-memory-powered-agentic-ai-that-learns-continuously-through-episodic-experiences-and-semantic-patterns-for-long-term-autonomy/) — episodic + semantic memory with continuous learning
