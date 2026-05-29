from dataclasses import dataclass

from shared.observability import ObservabilityTracker


class BudgetExceeded(Exception):
  pass

@dataclass
class Budget:
  max_usd: float

  def check(self, tracker: ObservabilityTracker):
      if tracker.session_total > self.max_usd:
          raise BudgetExceeded(f"${tracker.session_total:.4f} exceeds ${self.max_usd}")
