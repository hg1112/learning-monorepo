from time import time
import os
from dataclasses import dataclass
from typing import List

WIDTH = 43


@dataclass
class ObservedCallRecord:
    index: int
    input_tokens: int
    input_tokens_cost: float
    output_tokens: int
    output_tokens_cost: float
    cache_read: int
    cache_read_cost: float
    cache_write: int
    cache_write_cost: float
    latency: float

    def __str__(self) -> str:
        start = "| "
        end = " |"
        call_total = self.input_tokens_cost + self.output_tokens_cost + self.cache_read_cost + self.cache_write_cost
        lines = [
            f"Call #{self.index} -- {self.latency:.0f}ms",
            f"Input: {self.input_tokens} tokens  (${self.input_tokens_cost:.5f})",
            f"Output: {self.output_tokens} tokens  (${self.output_tokens_cost:.5f})",
            f"Cache read: {self.cache_read} | Cache write: {self.cache_write}",
            f"Call total: ${call_total:.5f}",
        ]
        return os.linesep.join(start + line + " " * (WIDTH - len(line)) + end for line in lines)

    def __repr__(self) -> str:
        return str(self)


def generate_record(index, usage, latency):
    PRICES = {
        "input":       3.00 / 1_000_000,
        "output":     15.00 / 1_000_000,
        "cache_write": 3.75 / 1_000_000,
        "cache_read":  0.30 / 1_000_000,
    }

    cache_write = usage.cache_creation_input_tokens or 0
    if usage.cache_creation is not None:
        cache_write += getattr(usage.cache_creation, "ephemeral_5m_input_tokens", 0) or 0
        cache_write += getattr(usage.cache_creation, "ephemeral_1h_input_tokens", 0) or 0

    cache_read = usage.cache_read_input_tokens or 0

    return ObservedCallRecord(
        index=index,
        input_tokens=usage.input_tokens,
        input_tokens_cost=usage.input_tokens * PRICES["input"],
        output_tokens=usage.output_tokens,
        output_tokens_cost=usage.output_tokens * PRICES["output"],
        cache_write=cache_write,
        cache_write_cost=cache_write * PRICES["cache_write"],
        cache_read=cache_read,
        cache_read_cost=cache_read * PRICES["cache_read"],
        latency=latency,
    )


class ObservabilityTracker:

    def __init__(self):
        self.index = 0
        self.calls: List[ObservedCallRecord] = []
        self.session_total = 0

    def __enter__(self):
        self.current_time = time()
        return self

    def record(self, response):
        end = time()
        latency = (end - self.current_time) * 1000
        self.index += 1
        self.current_time = time()

        call_record = generate_record(self.index, response.usage, latency)
        self.calls.append(call_record)
        self._print_call(call_record)

    def _print_call(self, rec: ObservedCallRecord):
        sep = "|" + "-" * WIDTH + "|"
        self.session_total = sum(
            r.input_tokens_cost + r.output_tokens_cost + r.cache_read_cost + r.cache_write_cost
            for r in self.calls
        )
        last_line = f"Session total: ${self.session_total:.5f} | {len(self.calls)} calls"
        print(os.linesep.join([
            sep,
            str(rec),
            sep,
            f"| {last_line}{' ' * (WIDTH - len(last_line) - 1)}|",
            sep,
        ]))

    def summary(self):
        sep = "|" + "-" * WIDTH + "|"
        session_total = sum(
            rec.input_tokens_cost + rec.output_tokens_cost + rec.cache_read_cost + rec.cache_write_cost
            for rec in self.calls
        )
        lines = [sep]
        for rec in self.calls:
            lines.append(str(rec))
            lines.append(sep)
        last_line = f"Session total: ${session_total:.5f} | {str(len(self.calls))} calls"
        lines.append(f"| {last_line}{' ' * (WIDTH - len(last_line) - 1)}|")
        lines.append(sep)
        print(os.linesep.join(lines))

    def __exit__(self, *args):
        pass
