import requests


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
    response = requests.get(
        "https://api.duckduckgo.com/",
        params={"q": query, "format": "json", "no_html": 1},
    )
    return response.json().get("AbstractText") or "No result found"


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