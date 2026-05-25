# Module 06 — Harness Engineering

**Goal:** Understand how coding harnesses (Pi, Claude Code) work, extend them with skills/extensions, and know when to build a custom harness vs. use an existing one.

---

## What is a Harness?

A harness is the scaffolding that wraps an AI agent to make it usable in a real workflow:

```
┌───────────────────────────────────────────────┐
│                   HARNESS                     │
│  - Session management (persist conversations) │
│  - Permission gates (approve/deny tool calls) │
│  - Context injection (AGENTS.md, SYSTEM.md)   │
│  - Skills (loadable capability packages)      │
│  - Hooks (run code before/after events)       │
│  - Observability (token/cost tracking)        │
│  - Multi-model routing                        │
└───────────────────────────────────────────────┘
                        │
               ┌────────▼────────┐
               │   LLM API       │
               │ (Anthropic, OAI,│
               │  Gemini, ...)   │
               └─────────────────┘
```

---

## Pi (`pi.dev`)

Pi is a minimal, terminal-based coding harness. Philosophy: **primitives, not features** — the core is small, everything else is an extension you write.

### Key Concepts in Pi

**Context Engineering Files**
- `AGENTS.md` — project-level instructions loaded into every session (like a persistent system prompt)
- `SYSTEM.md` — override/extend the model's system prompt for this project

These are the Pi equivalent of what you'd manually put in a system prompt. Put project context, coding conventions, and architecture notes here.

**Skills (Capability Packages)**
Skills are loadable modules that add new tools or behaviors. Loaded on-demand, not always active.

```typescript
// pi skill: skills/search.ts
export default {
  name: "search",
  description: "Web search capability",
  tools: [
    {
      name: "web_search",
      description: "Search the web",
      input_schema: { ... },
      execute: async ({ query }) => { ... }
    }
  ]
}
```

Load with `/skills search` in a session. Pi only adds their tokens when active.

**Extensions**
TypeScript modules that extend Pi's core behavior. This is how you add sub-agents, permission gates, custom UI — things Pi intentionally excludes from the core.

```typescript
// extension: before every tool call, log it
export default {
  hooks: {
    beforeToolCall: ({ tool, input }) => {
      log(`[TOOL] ${tool.name}(${JSON.stringify(input)})`)
    }
  }
}
```

**Session Tree**
Sessions are stored as trees (not linear logs). You can branch from any previous point — like git for conversations.

```
session/
  turn-1: "analyze this codebase"
  turn-2: "find the bug in auth"
    ├── branch-a: "fix with JWT"
    └── branch-b: "fix with sessions"  ← explore alternatives
```

**Multi-Model**
Pi supports 15+ providers. You can switch models mid-session or route different tasks to different models:
- Use Haiku for cheap classification/routing
- Use Sonnet for main reasoning
- Use Opus for complex synthesis

---

## Claude Code

Claude Code is Anthropic's own harness — more opinionated than Pi, with deeper integration into the Anthropic ecosystem.

### Key Concepts in Claude Code

**CLAUDE.md**
Project-level instructions (like Pi's `AGENTS.md`). Checked into the repo. Every session loads it automatically.

Best practices for CLAUDE.md:
- Architecture overview (what is this codebase)
- Build/test commands
- Conventions the model should follow
- What NOT to do (common mistakes)

**Hooks**
Shell commands that run in response to harness events. Configured in `settings.json`.

```json
{
  "hooks": {
    "PreToolUse": [{ "command": "echo 'About to call: $TOOL_NAME'" }],
    "PostToolUse": [{ "command": "log_tool_use.sh" }],
    "Stop": [{ "command": "notify-send 'Claude done'" }]
  }
}
```

Hook events:
| Event | When it fires |
|-------|--------------|
| `PreToolUse` | Before any tool call — can block the call |
| `PostToolUse` | After tool call completes |
| `Stop` | When the agent finishes a task |
| `Notification` | When Claude sends a notification |

**Skills**
Markdown files (`.md`) that Claude Code loads when invoked with `/skill-name`. A skill is a prompt that tells Claude how to perform a specific task.

```markdown
# my-skill.md
Do the following when invoked:
1. Read the current git diff
2. Analyze for security vulnerabilities
3. Output findings as a table
```

Placed in `.claude/skills/` or `~/.claude/skills/`.

**MCP Servers**
Model Context Protocol — a standard for connecting external tools/data sources to any compatible AI client. Claude Code supports MCP servers for:
- Connecting to databases
- Accessing external APIs
- Custom tool registries

**Subagents**
Claude Code can spawn subagent instances via the `Agent` tool. Each subagent has isolated context — useful for parallel tasks or protecting the main context from large results.

**Permission System**
Every tool call can be approved/denied. Permissions configured in `settings.json`:
```json
{
  "permissions": {
    "allow": ["Bash(git *)", "Read(*)", "Edit(src/*)"],
    "deny": ["Bash(rm -rf *)"]
  }
}
```

---

## Building a Custom Harness

When Pi and Claude Code aren't enough — you need your own harness for a product.

### Minimum Viable Harness

```python
class Harness:
    def __init__(self, agent, tools, memory, hooks):
        self.agent = agent          # the LLM + system prompt
        self.tools = tools          # tool registry
        self.memory = memory        # memory layers
        self.hooks = hooks          # pre/post call hooks
        self.tracker = ObsTracker() # token/cost tracking
    
    def run(self, user_input: str) -> str:
        self.hooks.pre_turn(user_input)
        self.memory.inject_relevant(self.agent.context)
        
        result = self.agent.loop(user_input, self.tools)
        
        self.memory.save_turn(user_input, result)
        self.hooks.post_turn(result, self.tracker)
        return result
```

### Harness Responsibilities Checklist

- [ ] Session persistence (save/load conversation history)
- [ ] Context injection (system prompt, memory, project docs)
- [ ] Tool dispatch (route tool calls to implementations)
- [ ] Budget enforcement (abort if cost exceeds limit)
- [ ] Hooks (extensibility without modifying core)
- [ ] Observability (log every call with tokens/cost/latency)
- [ ] Retry logic (transient failures, rate limits)
- [ ] Permission gates (approve/deny sensitive tool calls)
- [ ] Error recovery (agent gets stuck — detect and reset)

---

## What to Build

### `06_harness/demo.py`

Build a minimal Python harness with:
1. **AGENTS.md loading** — reads project instructions file on startup
2. **Hook system** — `pre_tool`, `post_tool`, `on_complete` hooks
3. **Skill loading** — load a skill module by name, adds its tools to the registry
4. **Session tree** — save turns, support branching from any turn index
5. **Budget gate** — configurable max cost, aborts the loop if exceeded

Then write a Pi-style `AGENTS.md` for the aie project directory and show the harness loading it.

---

## Pi vs. Claude Code vs. Custom

| | Pi | Claude Code | Custom |
|-|----|----|--------|
| Language | TypeScript extensions | Markdown skills + shell hooks | Anything |
| Multi-model | Yes (15+ providers) | Anthropic-primary | You decide |
| Session tree | Yes | No (linear) | You build it |
| MCP | No (excluded by design) | Yes | Optional |
| Best for | Multi-model experimentation | Anthropic-focused coding | Production products |

---

## Key Takeaways

- A harness is what transforms a raw API into a usable product — don't skip it
- AGENTS.md / CLAUDE.md are the highest-ROI investment for any project using AI agents
- Hooks are how you add observability, security, and custom behavior without forking the harness
- Build a custom harness only when you need to ship a product — use Pi or Claude Code for personal dev

---

## Resources

### Pi (`pi.dev`)
- [Pi.dev — Official Site](https://pi.dev/) — home page, quickstart, philosophy
- [Pi GitHub (coding-agent package)](https://github.com/badlogic/pi-mono/tree/main/packages/coding-agent) — source code for the core agent
- [Pi README](https://github.com/badlogic/pi-mono/blob/main/packages/coding-agent/README.md) — full documentation: modes, skills, extensions, AGENTS.md
- [Pi: A Minimal Terminal Coding Harness — Dev-Ore](https://www.dev-ore.com/blog/pi-dev-terminal-coding-harness/) — overview article
- [Pi Coding Agent: A Self-Documenting, Extensible AI Partner](https://dev.to/theoklitosbam7/pi-coding-agent-a-self-documenting-extensible-ai-partner-dn) — walkthrough of skills and extensions
- [Creating Pi Extensions — Skills Marketplace](https://lobehub.com/skills/zenobi-us-dotfiles-creating-pi-extensions) — extension development guide
- [Running Pi with Local Models (Gemma 4)](https://patloeber.com/gemma-4-pi-agent/) — use Pi with local/non-Anthropic models

### Claude Code Harness
- [Hooks Reference — Claude Code Docs](https://code.claude.com/docs/en/hooks) — official reference for all hook events, configuration, examples
- [Claude Code Full Stack Explained](https://alexop.dev/posts/understanding-claude-code-full-stack/) — MCP, skills, subagents, and hooks explained together
- [Claude Code Architecture: Six Harness Layers](https://mer.vin/2026/05/claude-code-architecture-explained-six-harness-layers-beyond-the-llm/) — deep dive into how Claude Code is structured
- [Claude Code CLI: Complete Guide — Hooks, MCP, Skills](https://blakecrosley.com/guides/claude-code) — practical guide with examples
- [Inside Claude Code: Tools, Memory, Hooks, MCP](https://www.penligent.ai/hackinglabs/inside-claude-code-the-architecture-behind-tools-memory-hooks-and-mcp/) — architecture internals
- [6 Months Tuning Claude Code](https://medium.com/data-science-collective/i-spent-6-months-tuning-claude-code-heres-the-exact-setup-that-finally-worked-b41c67628467) — real-world configuration lessons
- [ECC — Agent Harness Performance System](https://github.com/affaan-m/everything-claude-code) — open-source harness optimizations for Claude Code, Codex, Cursor

### Claude Code Extensions (Skills, MCP, Subagents)
- [Claude Code Extensions Explained — Towards AI](https://pub.towardsai.net/claude-code-extensions-explained-skills-mcp-hooks-subagents-agent-teams-plugins-9294907e84ff) — covers all six extension types in one article
- [Claude Code Skills & Harness Engineering — Paradime](https://www.paradime.io/guides/claude-code-skills-plugins-rules-guide) — guide to rules, plugins, MCP servers
