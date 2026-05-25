# Module 01 — Skills / Tools

**Goal:** Give an agent the ability to act on the world — not just generate text.

---

## Concepts

### What a Tool Is

A tool is a function the model can choose to call. You define it as a JSON schema; the model returns a structured `tool_use` block; you run the function and return the result.

```
You: "What's the weather in NYC?"
Model: [tool_use: get_weather(city="NYC")]    ← model decides to call a tool
You: [tool_result: "72°F, sunny"]            ← you run the function, return result
Model: "It's 72°F and sunny in NYC."         ← model uses result to answer
```

The model **never runs code itself** — it only requests a tool call. You execute it.

### Tool Schema

Every SDK uses the same JSON Schema structure:

```python
{
    "name": "get_weather",
    "description": "Get current weather for a city. Use when user asks about weather.",
    "input_schema": {
        "type": "object",
        "properties": {
            "city": {"type": "string", "description": "City name"},
            "units": {"type": "string", "enum": ["celsius", "fahrenheit"]}
        },
        "required": ["city"]
    }
}
```

**The description is the most important field.** The model uses it to decide when and how to call the tool.

### The Tool Call Loop

```
messages = [user_message]

while True:
    response = client.messages.create(tools=tools, messages=messages)
    
    if response.stop_reason == "end_turn":
        break                              # model is done
    
    if response.stop_reason == "tool_use":
        tool_call = extract_tool_call(response)
        result = dispatch(tool_call)       # you run the function
        
        messages.append(assistant_turn(response))   # model's tool_use block
        messages.append(tool_result_turn(result))   # your result
        # loop continues — model sees result and decides next step
```

### Multiple Tools + Tool Selection

Give the model several tools. It picks the right one based on context.
The model can also call multiple tools in one turn (parallel tool calls).

### Tool Error Handling

Tools fail. The model handles errors gracefully if you return them clearly:

```python
try:
    result = run_tool(tool_call)
    return {"status": "ok", "result": result}
except Exception as e:
    return {"status": "error", "message": str(e)}
    # model will retry, ask for clarification, or explain the failure
```

---

## What to Build

### `01_tools/demo.py`

1. **Single tool** — calculator tool, ask "what is 847 * 23?"
2. **Tool with side effects** — file writer tool, ask "save a note saying hello"
3. **Multi-tool** — give the model 3 tools; ask a question that requires 2 of them
4. **Parallel tool calls** — ask for weather in 3 cities at once; model calls tool 3 times

Each demo uses the shared observability layer — watch token usage grow as tool results are added to context.

### Tools to implement

```python
def calculator(expression: str) -> float: ...
def read_file(path: str) -> str: ...
def write_file(path: str, content: str) -> str: ...
def web_search(query: str) -> list[dict]: ...  # mock for now
```

---

## Cross-Ecosystem Note

| | Anthropic | OpenAI |
|-|-----------|--------|
| Schema field | `input_schema` | `parameters` |
| Tool call block | `tool_use` content block | `tool_calls` in message |
| Result role | `tool` role with `tool_use_id` | `tool` role with `tool_call_id` |
| Parallel calls | Yes — multiple blocks in one response | Yes |

Same loop, different field names.

---

## Key Takeaways

- Tools = the bridge between language and action
- The model picks tools based on descriptions — write them like documentation for a smart engineer
- Always return structured results (dict/JSON), not raw strings — easier for the model to reason about
- Tool errors are part of the conversation — don't crash, return the error

---

## Resources

### Official Docs
- [Anthropic Tool Use Guide](https://docs.anthropic.com/en/docs/build-with-claude/tool-use) — full reference: schemas, tool_use blocks, tool_result format, parallel calls
- [Tool Use Examples — Anthropic Cookbook](https://github.com/anthropics/anthropic-cookbook/tree/main/tool_use) — working Python examples for common tool patterns

### Articles
- [Implementing Tool Use from Scratch](https://www.dailydoseofds.com/ai-agents-crash-course-part-10-with-implementation/) — build the tool loop without a framework; shows exactly what happens at each step
- [Anthropic Agent SDK: What It Ships vs. What You Build](https://www.augmentcode.com/guides/anthropic-agent-sdk-what-ships-vs-what-you-build) — understand what the SDK handles vs. what you still write yourself

### YouTube
- [AssemblyAI — Function Calling Across Providers](https://www.youtube.com/@AssemblyAI) — practical comparison of tool call syntax across OpenAI, Anthropic, Gemini
