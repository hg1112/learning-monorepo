"""
TOPIC: Speculative Decoding
ByteDance Interview Prep — MLE Inference

Key interview questions:
  - How does speculative decoding work?
  - What is the acceptance criterion and why is it lossless?
  - When does it help / hurt?
  - What is self-speculative decoding (Medusa, EAGLE)?
"""

import numpy as np


def sample_token(logits: np.ndarray, temperature: float = 1.0) -> int:
    """Sample one token index from logits."""
    logits = logits / temperature
    logits -= logits.max()
    probs = np.exp(logits) / np.exp(logits).sum()
    return int(np.random.choice(len(probs), p=probs))


def speculative_decode_step(
    draft_tokens: list[int],
    draft_probs: np.ndarray,   # (gamma, vocab)  — draft model probabilities
    target_probs: np.ndarray,  # (gamma, vocab)  — target model probabilities
    rng: np.random.Generator,
) -> tuple[list[int], bool]:
    """
    Speculative decoding verification loop (DeepMind / Google Brain, 2023).

    Algorithm:
      1. Draft model generates gamma candidate tokens autoregressively (cheap).
      2. Target model scores all gamma tokens in ONE forward pass (parallel).
      3. Accept/reject each token with the modified rejection sampling criterion.
      4. If token t is rejected, resample from adjusted distribution and stop.

    Why lossless (same distribution as target autoregressive)?
      Accepted tokens follow min(p_target, p_draft) which, combined with the
      resampling of rejected tokens from max(0, p_target - p_draft) / Z,
      produces exactly p_target. See Leviathan et al. (2023) for the proof.

    Returns: accepted tokens, and whether all gamma were accepted.
    """
    gamma = len(draft_tokens)
    accepted = []

    for i in range(gamma):
        token = draft_tokens[i]
        q = draft_probs[i, token]    # draft prob of this token
        p = target_probs[i, token]   # target prob of this token

        # Accept with probability min(1, p/q)
        accept_prob = min(1.0, p / (q + 1e-9))
        if rng.random() < accept_prob:
            accepted.append(token)
        else:
            # Reject: resample from adjusted distribution
            adjusted = np.maximum(0, target_probs[i] - draft_probs[i])
            z = adjusted.sum()
            if z > 1e-9:
                adjusted /= z
                resampled = int(rng.choice(len(adjusted), p=adjusted))
            else:
                resampled = int(np.argmax(target_probs[i]))
            accepted.append(resampled)
            return accepted, False   # stop early

    # All gamma accepted — target model generates one bonus token
    bonus_token = sample_token(target_probs[-1])
    accepted.append(bonus_token)
    return accepted, True


# ─────────────────────────────────────────────
# Speedup analysis
# ─────────────────────────────────────────────

def expected_speedup(acceptance_rate: float, gamma: int,
                     draft_cost: float, target_cost: float) -> float:
    """
    Expected tokens per target forward pass with speculative decoding.

    Without spec decoding: 1 token per target call.
    With spec decoding:
      E[accepted] = gamma * alpha + 1  (alpha = per-token acceptance rate)
      Cost per batch = gamma * draft_cost + target_cost

    Speedup = E[accepted] / (gamma * draft_cost + target_cost)
              relative to 1 token / target_cost
    """
    # Expected number of accepted tokens per batch (geometric series)
    # P(accept exactly k) = alpha^k * (1-alpha), plus bonus
    expected_tokens = (1 - acceptance_rate ** (gamma + 1)) / (1 - acceptance_rate + 1e-12)
    latency_per_batch = gamma * draft_cost + target_cost
    latency_per_batch_autoregressive = target_cost  # 1 token per step

    speedup = (expected_tokens / latency_per_batch) / (1 / latency_per_batch_autoregressive)
    return speedup, expected_tokens


# ─────────────────────────────────────────────
# Medusa: self-speculative decoding
# ─────────────────────────────────────────────
"""
Medusa (Cai et al. 2024):
  - No separate draft model.
  - Attach K extra "Medusa heads" to the target LLM's last hidden state.
  - Each head predicts token at position t+1, t+2, ..., t+K in one forward pass.
  - Verify all K predictions simultaneously.
  - Acceptance via "tree attention": consider multiple candidate sequences.

Advantages over standard speculative decoding:
  - No separate model to maintain / synchronize.
  - Draft is free (piggybacks on target's forward pass).
  - Works well when the model is confident (long common n-grams).

EAGLE (Li et al. 2024):
  - Draft head is a small transformer that predicts FEATURE vectors (not just tokens).
  - Then projects features → token distribution.
  - Achieves higher acceptance rates than Medusa because features encode more info.
"""


# ─────────────────────────────────────────────
# Demo
# ─────────────────────────────────────────────
if __name__ == "__main__":
    np.random.seed(42)
    rng = np.random.default_rng(42)

    vocab = 100
    gamma = 4   # draft model generates 4 tokens per step

    print("=== Speculative Decoding Demo ===")
    total_accepted = 0
    total_steps = 20

    for step in range(total_steps):
        # Simulate draft model (small model — peaked distributions)
        draft_probs = np.random.dirichlet(np.ones(vocab) * 0.5, size=gamma)
        draft_tokens = [int(np.argmax(draft_probs[i])) for i in range(gamma)]

        # Simulate target model (closer to uniform, slightly correlated with draft)
        target_probs = 0.7 * draft_probs + 0.3 * np.random.dirichlet(np.ones(vocab), size=gamma)
        target_probs /= target_probs.sum(axis=-1, keepdims=True)

        accepted, all_good = speculative_decode_step(draft_tokens, draft_probs, target_probs, rng)
        total_accepted += len(accepted)

    avg_tokens = total_accepted / total_steps
    print(f"gamma={gamma}, avg tokens per target call: {avg_tokens:.2f}")
    print(f"Theoretical max (all accepted): {gamma + 1}")

    print("\n=== Speedup Analysis ===")
    for alpha in [0.5, 0.7, 0.9]:
        speedup, expected = expected_speedup(
            acceptance_rate=alpha, gamma=4,
            draft_cost=0.1,    # draft is 10x cheaper than target
            target_cost=1.0,
        )
        print(f"alpha={alpha:.1f}: E[tokens/call]={expected:.2f}, speedup={speedup:.2f}x")

"""
INTERVIEW TALKING POINTS:

Q: Why is speculative decoding correct (lossless)?
A: The rejection sampling scheme ensures that the marginal distribution of each
   accepted token is identical to what the target model would produce autoregressively.
   The proof uses the fact that min(p,q)/q * p + max(0,p-q)/Z is exactly p.

Q: When does speculative decoding NOT help?
A: 1. Draft model disagrees a lot (low acceptance rate alpha < 0.5)
      → Most tokens rejected, overhead of running draft wasted.
   2. Target model is fast (small model, already memory-bound)
      → The parallelism gain is small relative to draft overhead.
   3. Very diverse / creative outputs (high temperature) → low alpha.

Q: What gamma to choose?
A: Diminishing returns beyond gamma=4-8. Optimal gamma balances:
   acceptance rate, draft cost, target cost, and batch memory.

Q: How does ByteDance use this?
A: TikTok's recommendation/generation pipeline uses speculative decoding to
   reduce LLM inference latency. Smaller distilled models serve as drafts.
"""
