import os

import anthropic
from dotenv import load_dotenv

from shared.observability import ObservabilityTracker
from tools import *
from memory import *


remember("name", "Harish")
remember("lang_pref", "Python")
print(recall("name"))  # → "Harish"
print(recall("lang_pref"))  # → "Python"
print(recall("missing_key"))  # → "No memory found for key: missing_key"

# Save something
save_memory("favorite_db", "PostgreSQL")

# Retrieve from SQLite — survives the "restart"
print(search_memory("favorite_db"))
print(load_recent_memories(3))

docs = [
    "Python is great for data science and machine learning",
    "PostgreSQL is a powerful relational database",
    "The GIL prevents true multi-threading in CPython",
    "Docker containers make deployment reproducible",
    "Redis is an in-memory key-value store",
]
embed_documents(docs)
results = semantic_search("How does Python handle concurrency?", k=2)
print(results)

load_dotenv()

client = anthropic.Anthropic(api_key=os.getenv("ANTHROPIC_API_KEY"))

DISPATCH_TABLE = {
    "calculator": calculator,
    "read_file": read_file,
    "write_file": write_file,
    "web_search": web_search,
}

system = [
    {
        "type": "text",
        "text": "\n".join(
            [
                "ROLE : This is the system prompt to make this agent act like a generic purpose agent which can used as a personal assistant",
                """
                TOOLS :
                    calculator: for any arithmetic operation, use this tool to evaluate the expression. It should do for most of basic arithmetic in alignment with python syntax.
                    web_search: for any discovery of facts which is not provided in the existing context, search them for few search querys using this tool
                    read_file: for any read file operation, use this tool
                    write_file: for any write file operation, use this tool
                """,
                """
                PROCESS:
                    First understand the broad goal of the task.
                    Then dive deep into each sub task.
                    if it requires a tool use, use a appropriate tool .if no such tool, ask for user to share the required.
                    Be analytical for analytical tasks and creative for creative ones.
                """,
                """
                OUTPUT:
                    Return result as though a assistant is giving responses to a manager.
                    be critical and don't be a yes person - answer with the factually correct  data
                """,
                f"""
                Recent memories : {load_recent_memories(5)}
                Relevant context: {os.linesep.join(results)}
                """
            ]
        ),
        "cache_control": {"type": "ephemeral"},
    }
]


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
        "content" : response.content
    }

def tool_result_turn(results: list) -> dict:
    return {
        "role": "user",
        "content": results
    }


def call():
    MAX_ITERATIONS = 10
    counter = 0
    while True:
        if counter > MAX_ITERATIONS:
            raise RuntimeError(f"Agent Exceeded {MAX_ITERATIONS} iterations without finishing")
        response = turn()
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


def turn():
    with client.messages.stream(
        model="claude-haiku-4-5-20251001",
        max_tokens=1024,
        messages=messages,
        system=system,
        tools=tools,
    ) as stream:
        for text in stream.text_stream:
            print(text, end="", flush=True)

    response = stream.get_final_message()
    tracker.record(response)
    return response


with ObservabilityTracker() as tracker:
    messages = [{"role": "user", "content": "Search for what Python's GIL is and write a summary to gil_summary.txt"}]
    response = call()

