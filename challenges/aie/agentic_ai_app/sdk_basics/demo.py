import anthropic
import os
import logging
from dotenv import load_dotenv
from shared.observability import ObservabilityTracker

load_dotenv()

client = anthropic.Anthropic(
    api_key = os.getenv("ANTHROPIC_API_KEY")
)

system = [
    {
        "type": "text",
        "text" : '\n'.join(["This is the system prompt to make this agent act like a generic purpose agent which can used as a personal assistant"] * 50),
        "cache_control" : {"type": "ephemeral"}
    }
]



messages=[ {"role": "user", "content": "What is 2+2? One word answer."} ]

def turn():
    with client.messages.stream(
        model="claude-haiku-4-5-20251001",
        max_tokens=1024,
        messages=messages,
        system=system
    ) as stream:
        for text in stream.text_stream:
            print(text, end="", flush=True)

    response = stream.get_final_message()
    tracker.record(response)
    print(response.usage)
    print(response.content[0].text)
    return response

with ObservabilityTracker() as tracker:
    response = turn()

    messages.extend([
        {"role": "assistant", "content": response.content[0].text},
        {"role": "user", "content": "I know it is 5. Explain why it has to be four"},
    ])

    response = turn()

    messages.extend([
        {"role": "assistant", "content": response.content[0].text},
        {"role": "user", "content": "Explain the logic behind integer addition"},
    ])

    response = turn()

    messages.extend([
        {"role": "user", "content": "Tell me something of solar system, explaining each planet's best point"},
    ])

    response = turn()


