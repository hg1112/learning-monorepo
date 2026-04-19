"""
TOPIC: Continuous Batching + Request Scheduling
ByteDance Interview Prep — MLE Inference

Key interview questions:
  - What is static vs continuous batching?
  - How does continuous batching improve GPU utilization?
  - What is the iteration-level scheduling?
  - How do you handle variable-length sequences in a batch?
"""

import numpy as np
from dataclasses import dataclass, field
from collections import deque


# ─────────────────────────────────────────────
# Request model
# ─────────────────────────────────────────────

@dataclass
class Request:
    req_id: str
    prompt_len: int
    max_new_tokens: int
    tokens_generated: int = 0
    done: bool = False

    def step(self):
        self.tokens_generated += 1
        if self.tokens_generated >= self.max_new_tokens:
            self.done = True

    @property
    def total_len(self):
        return self.prompt_len + self.tokens_generated


# ─────────────────────────────────────────────
# Static batching (naive)
# ─────────────────────────────────────────────

def static_batching_simulate(requests: list[Request], batch_size: int = 4):
    """
    Static batching: group requests into fixed batches.
    All requests in a batch must finish before the next batch starts.
    GPU idles waiting for the longest request in each batch.
    """
    total_steps = 0
    wasted_steps = 0

    for i in range(0, len(requests), batch_size):
        batch = requests[i:i+batch_size]
        max_gen = max(r.max_new_tokens for r in batch)
        # All requests padded to max_gen regardless of their actual length
        steps_this_batch = max_gen
        wasted = sum(max_gen - r.max_new_tokens for r in batch)
        total_steps += steps_this_batch
        wasted_steps += wasted

    utilization = 1 - wasted_steps / (total_steps * batch_size)
    return total_steps, utilization


# ─────────────────────────────────────────────
# Continuous batching (iteration-level scheduling)
# ─────────────────────────────────────────────

class ContinuousBatchingScheduler:
    """
    Continuous batching (Orca, 2022):
    At every decode iteration, we can:
      1. Remove finished requests from the batch
      2. Add new waiting requests immediately (don't wait for batch to finish)

    This keeps GPU busy: when one request finishes, a new one takes its slot.
    Result: ~23x higher throughput than static batching (Orca paper).
    """

    def __init__(self, max_batch_size: int = 4, max_tokens_in_flight: int = 2048):
        self.max_batch_size = max_batch_size
        self.max_tokens_in_flight = max_tokens_in_flight
        self.waiting_queue: deque = deque()
        self.running: list[Request] = []
        self.finished: list[Request] = []

    def add_request(self, req: Request):
        self.waiting_queue.append(req)

    def _tokens_in_flight(self):
        return sum(r.total_len for r in self.running)

    def _can_admit(self, req: Request):
        if len(self.running) >= self.max_batch_size:
            return False
        if self._tokens_in_flight() + req.prompt_len > self.max_tokens_in_flight:
            return False
        return True

    def schedule_iteration(self):
        """
        Called once per decode step.
        Returns the set of requests to process this iteration.
        """
        # Remove finished requests
        done = [r for r in self.running if r.done]
        self.running = [r for r in self.running if not r.done]
        self.finished.extend(done)

        # Admit waiting requests into running batch
        while self.waiting_queue:
            candidate = self.waiting_queue[0]
            if self._can_admit(candidate):
                self.waiting_queue.popleft()
                self.running.append(candidate)
            else:
                break

        return list(self.running)

    def simulate(self, requests: list[Request]) -> dict:
        for r in requests:
            self.add_request(r)

        step = 0
        utilization_samples = []

        while self.waiting_queue or self.running:
            batch = self.schedule_iteration()
            if not batch:
                break

            active = len(batch)
            utilization_samples.append(active / self.max_batch_size)

            for r in batch:
                r.step()

            step += 1

        return {
            "total_steps": step,
            "avg_batch_utilization": np.mean(utilization_samples),
            "finished": len(self.finished),
        }


# ─────────────────────────────────────────────
# Chunked prefill
# ─────────────────────────────────────────────
"""
Problem: Long prompt prefills block the decode pipeline.
  A 10K-token prompt takes 1 prefill step that's ~100x more compute than decode.
  All decode requests stall during this time (head-of-line blocking).

Chunked Prefill (vLLM v2, Sarathi-Serve):
  - Split the prefill into K chunks (e.g., 512 tokens each)
  - Interleave prefill chunks with decode steps
  - Prefill chunk and decode requests share each iteration's batch
  - Decode latency stays smooth; long prompts don't cause spikes

Implementation:
  Each iteration: [prefill_chunk_tokens] + [decode_tokens_for_running_reqs]
  Total tokens per iteration capped by max_tokens_per_iter budget.
"""


def chunked_prefill_schedule(prompt_len: int, chunk_size: int = 512,
                              concurrent_decode_reqs: int = 8,
                              decode_tokens_per_req: int = 1):
    """Simulate iteration plan for chunked prefill."""
    num_chunks = (prompt_len + chunk_size - 1) // chunk_size
    plan = []
    for i in range(num_chunks):
        chunk_tokens = min(chunk_size, prompt_len - i * chunk_size)
        decode_tokens = concurrent_decode_reqs * decode_tokens_per_req
        plan.append({
            "iteration": i,
            "prefill_tokens": chunk_tokens,
            "decode_tokens": decode_tokens,
            "total_tokens": chunk_tokens + decode_tokens,
        })
    return plan


# ─────────────────────────────────────────────
# Demo
# ─────────────────────────────────────────────
if __name__ == "__main__":
    np.random.seed(42)

    print("=== Static vs Continuous Batching ===")
    reqs_static = [
        Request(f"r{i}", prompt_len=10, max_new_tokens=int(np.random.randint(10, 100)))
        for i in range(20)
    ]
    steps_static, util_static = static_batching_simulate(reqs_static, batch_size=4)
    print(f"Static:     {steps_static} steps, {util_static*100:.1f}% utilization")

    scheduler = ContinuousBatchingScheduler(max_batch_size=4, max_tokens_in_flight=4096)
    reqs_cont = [
        Request(f"r{i}", prompt_len=10, max_new_tokens=int(np.random.randint(10, 100)))
        for i in range(20)
    ]
    result = scheduler.simulate(reqs_cont)
    print(f"Continuous: {result['total_steps']} steps, "
          f"{result['avg_batch_utilization']*100:.1f}% utilization")

    print("\n=== Chunked Prefill Schedule (10K prompt) ===")
    plan = chunked_prefill_schedule(prompt_len=10_000, chunk_size=512, concurrent_decode_reqs=8)
    for p in plan[:3]:
        print(f"  iter {p['iteration']}: prefill={p['prefill_tokens']} + "
              f"decode={p['decode_tokens']} = {p['total_tokens']} tokens")
    print(f"  ... ({len(plan)} total iterations for 10K prefill)")

"""
INTERVIEW TALKING POINTS:

Q: What is the core insight behind continuous batching?
A: The decode phase is token-by-token. There's no reason to hold a GPU slot
   idle just because other requests in the batch haven't finished yet.
   At every iteration, evict done requests, admit new ones — keep GPU saturated.

Q: What metric does continuous batching optimize?
A: Throughput (tokens/second) by maximizing batch size at every iteration.
   Trade-off: can slightly increase P99 latency for some requests.

Q: What is head-of-line blocking in LLM serving?
A: A long prefill blocks all decode iterations until it's done.
   Chunked prefill fixes this by interleaving prefill chunks with decode steps.

Q: How does vLLM combine continuous batching + PagedAttention?
A: PagedAttention manages KV cache memory efficiently (no fragmentation).
   Continuous batching maximizes GPU utilization by keeping batch full.
   Together: near-theoretical GPU throughput for LLM serving.
"""
