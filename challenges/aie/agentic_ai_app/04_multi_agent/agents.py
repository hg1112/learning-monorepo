import asyncio
import json
import os

import anthropic
from dotenv import load_dotenv

from tools import tools, call_async
from report_state import ReportState
from shared.observability import ObservabilityTracker

load_dotenv()

client = anthropic.AsyncAnthropic(api_key=os.getenv("ANTHROPIC_API_KEY"))

async def run_research_agent(state: ReportState):
    with ObservabilityTracker() as tracker:
        system = os.linesep.join(
            [
                f"""
                    ROLE: Research Agent dedicated to doing web research on a topic of choice
                """,
                f"""
                    TOOLS: 
                    - web_search: search web for a given keywrod/query
                    - write_file: summarized the research from web and write to a file
                """,
                f"""
                    PROCESS: 
                    - Understand the topic and the questions being asked
                    - Search multiple aspects of it and study atleast 3 levels deep 
                    - Make sure to have examples for complex concepts
                """,
                f"""
                    OUTPUT:
                    make sure to write down the research summarized in a temp file using the tools provided
                """
            ]
        )

        research_tools = [t for t in tools if t["name"] in ["web_search", "write_file"]]
        messages = [
            {
                "role": "user",
                "content": f"Research {state.topic}"
            }
        ]

        response = await call_async(
            client = client,
            messages= messages,
            system = system,
            tools = research_tools,
            tracker = tracker,
        )

        state.research = response.content[0].text

        return state.research


async def run_code_agent(state: ReportState):
    with ObservabilityTracker() as tracker:
        system = os.linesep.join(
            [
                f"""
                    ROLE: Coding Agent dedicated to doing web research on a topic of choice
                """,
                f"""
                    TOOLS: 
                    - calculator: used to run math
                    - write_file: used to write the files in codebase
                """,
                f"""
                    PROCESS: 
                    - write code depending on the topic
                """,
                f"""
                    OUTPUT:
                    - properly formatted code with comments
                """
            ]
        )

        coding_tools = [t for t in tools if t["name"] in ["calculator", "write_file"]]

        messages = [
            {
                "role": "user",
                "content": f"Find or Generate code for {state.topic}"
            }
        ]

        response = await call_async(
            client=client,
            messages=messages,
            system=system,
            tools=coding_tools,
            tracker=tracker,
        )

        state.code_examples = response.content[0].text

        return state.code_examples


async def writer_agent(state: ReportState):
    with ObservabilityTracker() as tracker:
        system = os.linesep.join(
            [
                f"""
                    ROLE: Writing Agent for synthesizing research and code
                """,
                f"""
                    TOOLS: 
                    - read_file: read files
                    - write_file: write files
                """,
                f"""
                    PROCESS: 
                    - Read research agent output
                    - Read code agent output
                    - Synthesize code + agent output
                """,
                f"""
                    OUTPUT:
                    - Formalize the summary of research and code
                """
            ]
        )

        messages = [
            {
                "role": "user",
                "content": ''.join([
                    f"Synthesize results for {state.topic}",
                    f"Research : {state.research}",
                    f"Code : {state.code_examples}",
                ])
            }
        ]

        response = await call_async(
            client=client,
            messages=messages,
            system=system,
            tracker=tracker,
            tools=[]
        )

        state.draft = response.content[0].text
        return state.draft


async def critic_agent(state: ReportState):
    with ObservabilityTracker() as tracker:
        system = os.linesep.join(
            [
                f"""
                    ROLE: Critic Agent for reviewed draft
                """,
                f"""
                    TOOLS: 
                    - web_search: find evidences for statements
                    - calculator: evaludate the math results
                    - read_file: read files
                    - write_file: write files
                """,
                f"""
                    PROCESS: 
                    - Read research + code agent summary
                    - Review all the facts and dimensions of the results
                    - Verify anything out of the norm
                    - Approve fi everything looks good 
                """,
                """
                    OUTPUT:
                    - {"approved" : bool, "feedback" : str} 
                """
            ]
        )


        messages = [
            {
                "role": "user",
                "content": ''.join([
                    f"Review results for {state.topic}",
                    f"Draft : {state.draft}"
                ])
            }
        ]

        response = await call_async(
            client=client,
            messages=messages,
            system=system,
            tracker=tracker,
            tools=[]
        )

        text = response.content[0].text
        text = text.strip().removeprefix("```json").removeprefix("```").removesuffix("```").strip()
        value = json.loads(text)

        state.approved = value['approved']
        state.critique = value['feedback']
        return value


async def orchestrator(topic: str):
    state = ReportState(
        topic=topic,
        research="",
        code_examples="",
        draft="",
        critique="",
        approved=False,
        iterations=0
    )
    await asyncio.gather(
        run_research_agent(state),
        run_code_agent(state)
    )

    MAX_REVISIONS = 20
    while not state.approved and state.iterations < MAX_REVISIONS:
        await writer_agent(state)
        await critic_agent(state)
        state.iterations += 1

    return state


