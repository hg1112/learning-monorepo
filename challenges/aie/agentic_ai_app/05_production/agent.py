import os

import anthropic
from dotenv import load_dotenv

from shared.budget import Budget, BudgetExceeded
from shared.observability import ObservabilityTracker
from tools import *

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
                "This is the system prompt to make this agent act like a generic purpose agent which can used as a personal assistant"
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


def call(messages, tracker, budget):
    MAX_ITERATIONS = 10
    while MAX_ITERATIONS > 0:
        try:
            response = turn(messages, tracker, budget)
            if response.stop_reason == "end_turn":
                break
            if response.stop_reason == "tool_use":
                messages.append(assistant_turn(response))
                tool_call_blocks = extract_tool_calls(response)
                tool_results = []
                for tool_use_block in tool_call_blocks:
                    tool_results.append(dispatch(tool_use_block))
                messages.append(tool_result_turn(tool_results))
            MAX_ITERATIONS -= 1
        except BudgetExceeded:
            print("Budget Exceeded")
            return
    return response

def turn(messages, tracker, budget):
    response = client.messages.create(
        model="claude-haiku-4-5-20251001",
        max_tokens=1024,
        messages=messages,
        system=system,
        tools=tools,
    )
    tracker.record(response)
    if budget:
        budget.check(tracker)
    return response

def run_query(query: str, budget: Budget) -> str:
    with ObservabilityTracker() as tracker:
        messages = [
            {
                "role": "user",
                "content": query
            }
        ]
        response = call(messages, tracker, budget)
        if not response:
            return ""
        return response.content[0].text
