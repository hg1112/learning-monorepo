from time import sleep

import anthropic
from duckduckgo_search import DDGS


def calculator(expression: str) -> float:
    # eval executes arbitrary code — never use with untrusted input in production
    return eval(expression)


def read_file(path: str) -> str:
    with open(path, "r") as f:
        line = f.read()

    return line


def write_file(path: str, content: str) -> str:
    with open(path, "w") as f:
        f.write(content)
    return f"File '{path}' written successfully"


def web_search(query: str) -> str:
    print(f"Searching web... {query}")
    with DDGS() as ddgs:
        for i in range(3):
            results = list(ddgs.text(query, max_results=3))
            if results:
                return "\n\n".join(f"{r['title']}: {r['body']}" for r in results)
            sleep(2 ** i)
    return "No results found"


tools = [
    {
        "name": "calculator",
        "description": "Perform a mathematical calculation.",
        "input_schema": {
            "type": "object",
            "properties": {
                "expression": {"type": "string"},
            },
            "required": ["expression"],
        },
    },
    {
        "name": "read_file",
        "description": "Read the contents of a file.",
        "input_schema": {
            "type": "object",
            "properties": {
                "path": {"type": "string"},
            },
            "required": ["path"],
        },
    },
    {
        "name": "write_file",
        "description": "Write content to a file.",
        "input_schema": {
            "type": "object",
            "properties": {
                "path": {"type": "string"},
                "content": {"type": "string"},
            },
            "required": ["path", "content"],
        },
    },
    {
        "name": "web_search",
        "description": "Search the web for information.",
        "input_schema": {
            "type": "object",
            "properties": {
                "query": {"type": "string"},
            },
            "required": ["query"],
        },
    },
]

DISPATCH_TABLE = {
    "calculator": calculator,
    "read_file": read_file,
    "write_file": write_file,
    "web_search": web_search,
}


def dispatch(tool_use_block):
    fn = DISPATCH_TABLE[tool_use_block.name]
    result = fn(**tool_use_block.input)
    return {
        "type": "tool_result",
        "tool_use_id": tool_use_block.id,
        "content": str(result)
    }


def extract_tool_calls(response) -> list:
    return [block for block in response.content if block.type == "tool_use"]


def assistant_turn(response) -> dict:
    return {
        "role": "assistant",
        "content": response.content
    }


def tool_result_turn(results: list) -> dict:
    return {
        "role": "user",
        "content": results
    }


def turn(client, messages, system, tools, tracker):
    response = client.messages.create(
        model="claude-haiku-4-5-20251001",
        max_tokens=1024,
        messages=messages,
        system=system,
        tools=tools,
    )
    tracker.record(response)
    return response


def call(client: anthropic.Anthropic, system, query, tools, tracker):
    MAX_ITERATIONS = 10
    counter = 0
    messages = [
        {
            "role": "user",
            "content": query
        }
    ]
    while True:
        if counter > MAX_ITERATIONS:
            raise RuntimeError(f"Agent Exceeded {MAX_ITERATIONS} iterations without finishing")
        response = turn(client, messages, system, tools, tracker)
        counter += 1
        if response.stop_reason == "end_turn":
            break
        if response.stop_reason == "tool_use":
            messages.append(assistant_turn(response))
            tool_call_blocks = extract_tool_calls(response)
            tool_results = []
            for tool_use_block in tool_call_blocks:
                tool_results.append(dispatch(tool_use_block))
            messages.append(tool_result_turn(tool_results))
    return response


async def turn_async(client: anthropic.AsyncAnthropic, messages, system, tools, tracker):
    if tools :
        response = await client.messages.create(
            model="claude-haiku-4-5-20251001",
            max_tokens=1024,
            messages=messages,
            system=system,
            tools=tools,
        )
    else:
        response = await client.messages.create(
        model="claude-haiku-4-5-20251001",
        max_tokens=1024,
        messages=messages,
        system=system
    )
    tracker.record(response)
    return response


async def call_async(client: anthropic.AsyncAnthropic, messages, system, tools, tracker):
    MAX_ITERATIONS = 30
    counter = 0
    while True:
        if counter > MAX_ITERATIONS:
            raise RuntimeError(f"Agent Exceeded {MAX_ITERATIONS} iterations without finishing")
        response = await turn_async(client, messages, system, tools, tracker)
        counter += 1
        if response.stop_reason == "end_turn":
            break
        if response.stop_reason == "tool_use":
            messages.append(assistant_turn(response))
            tool_call_blocks = extract_tool_calls(response)
            tool_results = []
            for tool_use_block in tool_call_blocks:
                tool_results.append(dispatch(tool_use_block))
                counter += 1
            messages.append(tool_result_turn(tool_results))
    return response
