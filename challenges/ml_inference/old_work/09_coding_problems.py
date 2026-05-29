"""
TOPIC: Coding Problems — ML-Adjacent Interview Questions
ByteDance Interview Prep — MLE Inference

ByteDance typically asks:
  1. LeetCode medium/hard with ML twist
  2. Matrix / tensor manipulation
  3. Implementing ML building blocks from scratch
  4. Algorithm design for inference scenarios

All solutions below are O(n) or better unless stated.
Run: python 09_coding_problems.py
"""

import numpy as np
from collections import defaultdict, deque
import heapq


# ═══════════════════════════════════════════════════════════════
# PROBLEM 1: Implement Softmax (numerically stable)
# ═══════════════════════════════════════════════════════════════
def softmax(x: np.ndarray, axis: int = -1) -> np.ndarray:
    """
    Numerically stable softmax.
    Key: subtract max before exp to prevent overflow.
    Time: O(n), Space: O(n)
    """
    shifted = x - x.max(axis=axis, keepdims=True)
    exp_x = np.exp(shifted)
    return exp_x / exp_x.sum(axis=axis, keepdims=True)


def test_softmax():
    x = np.array([1.0, 2.0, 3.0])
    out = softmax(x)
    assert abs(out.sum() - 1.0) < 1e-6
    assert abs(out[2] - 0.6652) < 1e-4

    # Test numerical stability with large values
    x_large = np.array([1000.0, 1001.0, 1002.0])
    out_large = softmax(x_large)
    assert not np.any(np.isnan(out_large))
    print("P1 softmax: PASS")


# ═══════════════════════════════════════════════════════════════
# PROBLEM 2: Top-K with heap (used in beam search, ANN)
# ═══════════════════════════════════════════════════════════════
def top_k_indices(scores: list, k: int) -> list:
    """
    Return indices of top-k scores.
    Time: O(n log k), Space: O(k)
    Better than sort O(n log n) when k << n.
    """
    return heapq.nlargest(k, range(len(scores)), key=lambda i: scores[i])


def test_top_k():
    scores = [3.1, 1.2, 4.5, 2.7, 0.8, 4.1, 3.9]
    result = top_k_indices(scores, k=3)
    assert set(result) == {2, 5, 6}  # indices of 4.5, 4.1, 3.9
    print("P2 top_k: PASS")


# ═══════════════════════════════════════════════════════════════
# PROBLEM 3: Beam Search
# ═══════════════════════════════════════════════════════════════
def beam_search(log_prob_fn, vocab_size: int, beam_width: int = 3,
                max_len: int = 5, eos_token: int = 0) -> list:
    """
    Beam search: keep top-B sequences at each step.
    log_prob_fn(tokens) → log probs over vocab (shape: vocab_size)

    Time: O(max_len * beam_width * vocab_size)
    Space: O(beam_width * max_len)
    """
    # Each beam: (cumulative_log_prob, token_sequence)
    beams = [(0.0, [])]
    completed = []

    for _ in range(max_len):
        candidates = []
        for score, tokens in beams:
            if tokens and tokens[-1] == eos_token:
                completed.append((score, tokens))
                continue
            log_probs = log_prob_fn(tokens)   # (vocab_size,)
            # Expand: consider all vocab options
            for token_id in range(vocab_size):
                new_score = score + log_probs[token_id]
                candidates.append((new_score, tokens + [token_id]))

        if not candidates:
            break
        # Keep top beam_width
        beams = sorted(candidates, key=lambda x: x[0], reverse=True)[:beam_width]

    completed.extend(beams)
    completed.sort(key=lambda x: x[0], reverse=True)
    return completed[0][1] if completed else []


def test_beam_search():
    np.random.seed(0)
    vocab = 5
    # Deterministic: always prefer token 2
    def fake_lm(tokens):
        lp = np.array([-5.0] * vocab, dtype=float)
        lp[2] = -0.1  # strongly prefer token 2
        return lp

    result = beam_search(fake_lm, vocab_size=vocab, beam_width=2, max_len=4, eos_token=0)
    assert result[0] == 2
    print("P3 beam_search: PASS")


# ═══════════════════════════════════════════════════════════════
# PROBLEM 4: Sliding window max (attention score aggregation)
# ═══════════════════════════════════════════════════════════════
def sliding_window_max(nums: list, k: int) -> list:
    """
    Maximum in each window of size k.
    Used in: sliding window attention, feature windowing.
    Time: O(n), Space: O(k) using monotonic deque.
    """
    dq = deque()  # stores indices, front is always max
    result = []

    for i, val in enumerate(nums):
        # Remove elements outside window
        while dq and dq[0] <= i - k:
            dq.popleft()
        # Maintain decreasing order in deque
        while dq and nums[dq[-1]] <= val:
            dq.pop()
        dq.append(i)

        if i >= k - 1:
            result.append(nums[dq[0]])
    return result


def test_sliding_window_max():
    assert sliding_window_max([1, 3, -1, -3, 5, 3, 6, 7], 3) == [3, 3, 5, 5, 6, 7]
    assert sliding_window_max([1], 1) == [1]
    print("P4 sliding_window_max: PASS")


# ═══════════════════════════════════════════════════════════════
# PROBLEM 5: LRU Cache (KV cache eviction policy)
# ═══════════════════════════════════════════════════════════════
class LRUCache:
    """
    O(1) get and put.
    Used for: KV cache eviction, embedding cache, feature cache.
    Implementation: hashmap + doubly-linked list.
    """
    class Node:
        def __init__(self, key=0, val=0):
            self.key, self.val = key, val
            self.prev = self.next = None

    def __init__(self, capacity: int):
        self.cap = capacity
        self.cache = {}
        self.head = self.Node()  # dummy head (most recent)
        self.tail = self.Node()  # dummy tail (least recent)
        self.head.next = self.tail
        self.tail.prev = self.head

    def _remove(self, node):
        node.prev.next = node.next
        node.next.prev = node.prev

    def _insert_front(self, node):
        node.next = self.head.next
        node.prev = self.head
        self.head.next.prev = node
        self.head.next = node

    def get(self, key: int) -> int:
        if key not in self.cache:
            return -1
        node = self.cache[key]
        self._remove(node)
        self._insert_front(node)
        return node.val

    def put(self, key: int, value: int):
        if key in self.cache:
            self._remove(self.cache[key])
        node = self.Node(key, value)
        self.cache[key] = node
        self._insert_front(node)
        if len(self.cache) > self.cap:
            lru = self.tail.prev
            self._remove(lru)
            del self.cache[lru.key]


def test_lru():
    cache = LRUCache(2)
    cache.put(1, 1)
    cache.put(2, 2)
    assert cache.get(1) == 1
    cache.put(3, 3)       # evicts key 2
    assert cache.get(2) == -1
    cache.put(4, 4)       # evicts key 1
    assert cache.get(1) == -1
    assert cache.get(3) == 3
    assert cache.get(4) == 4
    print("P5 LRU Cache: PASS")


# ═══════════════════════════════════════════════════════════════
# PROBLEM 6: Cosine similarity top-K (two-tower retrieval)
# ═══════════════════════════════════════════════════════════════
def cosine_top_k(query: np.ndarray, corpus: np.ndarray, k: int) -> list:
    """
    Find top-k corpus vectors most similar to query.
    Assumes L2-normalized embeddings → cosine = dot product.
    Time: O(n*d + n log k)
    """
    query_norm = query / (np.linalg.norm(query) + 1e-8)
    corpus_norms = np.linalg.norm(corpus, axis=1, keepdims=True) + 1e-8
    corpus_normalized = corpus / corpus_norms

    similarities = corpus_normalized @ query_norm    # (n,)
    top_k_idx = np.argpartition(similarities, -k)[-k:]  # O(n), not O(n log n)
    top_k_idx = top_k_idx[np.argsort(similarities[top_k_idx])[::-1]]
    return list(zip(top_k_idx, similarities[top_k_idx]))


def test_cosine_top_k():
    np.random.seed(42)
    query = np.array([1.0, 0.0, 0.0])
    corpus = np.array([[1.0, 0.0, 0.0], [0.0, 1.0, 0.0], [0.9, 0.1, 0.0]])
    results = cosine_top_k(query, corpus, k=2)
    # Most similar should be index 0 (exact match), then index 2
    assert results[0][0] == 0
    assert results[1][0] == 2
    print("P6 cosine_top_k: PASS")


# ═══════════════════════════════════════════════════════════════
# PROBLEM 7: Weighted random sampling (nucleus/top-p sampling)
# ═══════════════════════════════════════════════════════════════
def nucleus_sample(logits: np.ndarray, p: float = 0.9, temperature: float = 1.0) -> int:
    """
    Top-p (nucleus) sampling:
    1. Sort tokens by probability descending
    2. Take smallest set of tokens whose cumulative prob >= p
    3. Sample from that set

    Prevents sampling very unlikely tokens, preserves diversity vs top-k.
    """
    scaled = logits / temperature
    probs = softmax(scaled)

    sorted_indices = np.argsort(probs)[::-1]
    sorted_probs = probs[sorted_indices]
    cumulative = np.cumsum(sorted_probs)

    # Find cutoff: first index where cumulative prob >= p
    cutoff = np.searchsorted(cumulative, p) + 1
    nucleus_indices = sorted_indices[:cutoff]
    nucleus_probs = probs[nucleus_indices]
    nucleus_probs /= nucleus_probs.sum()

    return int(np.random.choice(nucleus_indices, p=nucleus_probs))


def test_nucleus():
    np.random.seed(42)
    logits = np.array([2.0, 1.0, 0.5, 0.1, 0.05, 0.02])
    results = [nucleus_sample(logits, p=0.9) for _ in range(1000)]
    # Top token (index 0) should be most common
    counts = defaultdict(int)
    for r in results:
        counts[r] += 1
    assert counts[0] > 500
    print("P7 nucleus_sample: PASS")


# ═══════════════════════════════════════════════════════════════
# PROBLEM 8: Batch padding + attention mask
# ═══════════════════════════════════════════════════════════════
def pad_batch(sequences: list, pad_token: int = 0) -> tuple:
    """
    Left-pad sequences to same length, return attention mask.
    Returns: (padded_batch, attention_mask) both (batch, max_len)
    """
    max_len = max(len(s) for s in sequences)
    padded = []
    masks = []
    for seq in sequences:
        pad_len = max_len - len(seq)
        padded.append([pad_token] * pad_len + list(seq))
        masks.append([0] * pad_len + [1] * len(seq))
    return np.array(padded), np.array(masks)


def test_padding():
    seqs = [[1, 2, 3], [4, 5], [6, 7, 8, 9]]
    padded, mask = pad_batch(seqs)
    assert padded.shape == (3, 4)
    assert mask[1].tolist() == [0, 0, 1, 1]
    assert padded[1].tolist() == [0, 0, 4, 5]
    print("P8 pad_batch: PASS")


# ═══════════════════════════════════════════════════════════════
# PROBLEM 9: Implement LayerNorm
# ═══════════════════════════════════════════════════════════════
def layer_norm(x: np.ndarray, gamma: np.ndarray, beta: np.ndarray,
               eps: float = 1e-5) -> np.ndarray:
    """
    Layer normalization: normalize across feature dimension.
    Stabilizes training of deep networks.
    Time: O(n*d), Space: O(d)
    """
    mean = x.mean(axis=-1, keepdims=True)
    var = x.var(axis=-1, keepdims=True)
    x_norm = (x - mean) / np.sqrt(var + eps)
    return gamma * x_norm + beta


def test_layer_norm():
    x = np.array([[1.0, 2.0, 3.0, 4.0]])
    gamma = np.ones(4)
    beta = np.zeros(4)
    out = layer_norm(x, gamma, beta)
    assert abs(out.mean()) < 1e-5
    assert abs(out.var() - 1.0) < 0.1
    print("P9 layer_norm: PASS")


# ═══════════════════════════════════════════════════════════════
# PROBLEM 10: Token budget scheduler (rate limiting inference)
# ═══════════════════════════════════════════════════════════════
class TokenBudgetScheduler:
    """
    Rate-limit inference by token budget (tokens/second).
    Used in production inference to prevent GPU OOM from request bursts.

    Algorithm: Token Bucket
      - Bucket refills at `rate` tokens/second
      - Burst allowed up to `capacity` tokens
      - Request for `n` tokens: drain n tokens or reject
    """
    def __init__(self, capacity: float, rate: float):
        self.capacity = capacity
        self.rate = rate
        self.tokens = capacity
        self.last_refill = 0.0

    def refill(self, current_time: float):
        elapsed = current_time - self.last_refill
        self.tokens = min(self.capacity, self.tokens + elapsed * self.rate)
        self.last_refill = current_time

    def request(self, num_tokens: int, current_time: float) -> bool:
        """Returns True if request is approved, False if rate-limited."""
        self.refill(current_time)
        if self.tokens >= num_tokens:
            self.tokens -= num_tokens
            return True
        return False


def test_token_bucket():
    scheduler = TokenBudgetScheduler(capacity=100, rate=50)  # 50 tokens/sec
    assert scheduler.request(80, current_time=0.0) == True   # drain 80
    assert scheduler.request(80, current_time=0.0) == False  # only 20 left
    assert scheduler.request(80, current_time=2.0) == True   # 2s → refilled 100
    print("P10 TokenBudgetScheduler: PASS")


# ─────────────────────────────────────────────
# Run all tests
# ─────────────────────────────────────────────
if __name__ == "__main__":
    print("Running all coding problem tests...\n")
    test_softmax()
    test_top_k()
    test_beam_search()
    test_sliding_window_max()
    test_lru()
    test_cosine_top_k()
    test_nucleus()
    test_padding()
    test_layer_norm()
    test_token_bucket()
    print("\nAll tests passed!")
