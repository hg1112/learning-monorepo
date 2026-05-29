import functools
import os

import anthropic
from dotenv import load_dotenv
from numpy import mean

from agent import call, run_query
from shared.observability import ObservabilityTracker

TEST_CASES = [
    {
        "input": "What is the capital of California",
        "expected": "Sacramento",
        "match": "exact"
    },
    {
        "input": "What is full name of Luffy",
        "expected": "Monkey D. Luffy",
        "match": "exact"
    },
    {
        "input": "Who are the crewmates of StrawHat Luffy",
        "expected": "Zoro Sanji Nami Chopper Brook Usopp",
        "match": "contains"
    },
    {
        "input": "What is date of Indian Independence day? ",
        "expected": "August 15",
        "match": "contains"
    },
    {
        "input": "Tell me about value of 2+2",
        "expected": "4",
        "match": "exact"
    }
]

load_dotenv()
client = anthropic.Anthropic(api_key=os.getenv("API_KEY"))


def score(result, case):
    match = case["match"]
    expected = case["expected"]
    if match == "exact":
        return 1 if expected in result else 0
    elif match == "contains":
        return 1 if functools.reduce(lambda a, b: a and b, [word in result for word in expected.split()]) else 0
    elif match == "regex":
        return 0
    else:
        messages = [
            'Rate this answer 1-5 and explain why in a json format {"score": <score between 1 to 5>, "why" "reason for the score"}'
        ]
        with ObservabilityTracker() as tracker:
            response = call(messages, tracker, None)
        if 'score' in response:
            return response['score']
        return 0


def run_evals(agent_fn):
    scores = []
    for case in TEST_CASES:
        result = agent_fn(case["input"], None)
        score_value = score(result, case)
        scores.append(score_value)
        print(f"Case completed with score {score_value} : \n {case}")
    print(f"Total {len(scores)} :  Success {len([s for s in scores if s > 0.5])} , Failed {len([s for s in scores if s <= 0.5])}")
    return mean(scores)


print(run_evals(run_query))
