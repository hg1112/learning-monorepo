import asyncio
from agents import orchestrator

result = asyncio.run(
    orchestrator("python async patterns")
)

print(result)