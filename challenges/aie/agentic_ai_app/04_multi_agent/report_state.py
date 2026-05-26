from dataclasses import dataclass


@dataclass
class ReportState:
    topic: str
    research: str
    code_examples: str
    draft: str
    critique: str
    approved: bool = False
    iterations: int = 0