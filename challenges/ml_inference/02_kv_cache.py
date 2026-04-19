"""
TOPIC: KV Cache — Prefill vs Decode Phase
ByteDance Interview Prep — MLE Inference

Key interview questions:
  - What is the KV cache and why do we need it?
  - What is the prefill vs decode phase?
  - How much memory does KV cache use?
  - What is continuous batching?
"""

import numpy as np


# ─────────────────────────────────────────────
# WITHOUT KV Cache — naive autoregressive
# ─────────────────────────────────────────────
def attention_no_cache(all_tokens_so_far, W_q, W_k, W_v, W_o, d_k):
    """Recomputes K, V for all previous tokens every step — O(n^2) total."""
    Q = all_tokens_so_far @ W_q  # (n, d_model)
    K = all_tokens_so_far @ W_k
    V = all_tokens_so_far @ W_v

    # Only the LAST query matters for next token prediction
    q = Q[-1:, :]  # (1, d_k)
    scores = (q @ K.T) / np.sqrt(d_k)  # (1, n)
    weights = np.exp(scores - scores.max())
    weights /= weights.sum()
    out = weights @ V  # (1, d_v)
    return out @ W_o


# ─────────────────────────────────────────────
# WITH KV Cache — amortized O(n) per step
# ─────────────────────────────────────────────
class KVCache:
    """
    Stores pre-computed K and V projections for all past tokens.
    At each decode step, only the new token's K,V are computed and appended.

    Memory cost per token: 2 * num_layers * num_heads * d_head * bytes_per_element
    For LLaMA-70B (80 layers, 64 heads, 128 d_head, fp16):
      per token = 2 * 80 * 64 * 128 * 2 bytes = 2.6 MB
      2048 context = ~5 GB just for KV cache
    """

    def __init__(self):
        self.K_cache = None  # (seq, d_k)
        self.V_cache = None  # (seq, d_v)

    def update(self, new_k, new_v):
        """Append one token's K and V to the cache."""
        if self.K_cache is None:
            self.K_cache = new_k
            self.V_cache = new_v
        else:
            self.K_cache = np.vstack([self.K_cache, new_k])
            self.V_cache = np.vstack([self.V_cache, new_v])

    def size(self):
        return 0 if self.K_cache is None else self.K_cache.shape[0]


class TransformerWithKVCache:
    def __init__(self, d_model, d_k):
        self.d_k = d_k
        np.random.seed(0)
        self.W_q = np.random.randn(d_model, d_k) * 0.1
        self.W_k = np.random.randn(d_model, d_k) * 0.1
        self.W_v = np.random.randn(d_model, d_k) * 0.1
        self.W_o = np.random.randn(d_k, d_model) * 0.1
        self.cache = KVCache()

    def prefill(self, prompt_tokens):
        """
        PREFILL PHASE: Process entire prompt in parallel (like training).
        Populates the KV cache for all prompt tokens.
        Returns output for the last prompt token (which predicts first new token).
        """
        Q = prompt_tokens @ self.W_q  # (prompt_len, d_k)
        K = prompt_tokens @ self.W_k
        V = prompt_tokens @ self.W_v

        # Store prompt K, V in cache
        self.cache.K_cache = K
        self.cache.V_cache = V

        # Attention for the last token (causal: sees all prompt tokens)
        q = Q[-1:, :]
        scores = (q @ K.T) / np.sqrt(self.d_k)
        weights = np.exp(scores - scores.max())
        weights /= weights.sum()
        out = (weights @ V) @ self.W_o
        print(f"  Prefill: processed {len(prompt_tokens)} tokens, cache size = {self.cache.size()}")
        return out

    def decode_one_token(self, new_token):
        """
        DECODE PHASE: Process one new token, reuse cached K,V.
        Only computes K,V for the single new token — O(1) per step.
        """
        q = (new_token @ self.W_q)[None, :]   # (1, d_k)
        new_k = (new_token @ self.W_k)[None, :]
        new_v = (new_token @ self.W_v)[None, :]

        self.cache.update(new_k, new_v)

        # Attend over entire cache (all past tokens + current)
        scores = (q @ self.cache.K_cache.T) / np.sqrt(self.d_k)  # (1, n)
        weights = np.exp(scores - scores.max())
        weights /= weights.sum()
        out = (weights @ self.cache.V_cache) @ self.W_o
        print(f"  Decode step: cache size = {self.cache.size()}")
        return out


# ─────────────────────────────────────────────
# PagedAttention concept (vLLM)
# ─────────────────────────────────────────────
"""
Problem with naive KV cache:
  - Must pre-allocate max_seq_len memory per request upfront
  - If request ends early, that memory is wasted (internal fragmentation)
  - Can't share KV cache between requests (e.g., for shared system prompts)

PagedAttention (vLLM) solution:
  - Divide KV cache into fixed-size "pages" (blocks), e.g., 16 tokens/block
  - Maintain a block table mapping logical → physical blocks
  - Allocate pages on demand, free immediately when request completes
  - Allows copy-on-write sharing (e.g., same prompt prefix across requests)

Result: near-zero memory waste, 2-4x more requests served simultaneously
"""

class PagedKVCache:
    """Simplified PagedAttention-style block manager."""

    def __init__(self, block_size, num_blocks, d_k):
        self.block_size = block_size
        self.num_blocks = num_blocks
        self.d_k = d_k

        # Physical memory pool
        self.k_pool = np.zeros((num_blocks, block_size, d_k))
        self.v_pool = np.zeros((num_blocks, block_size, d_k))

        self.free_blocks = list(range(num_blocks))
        # request_id → list of physical block indices
        self.block_tables = {}

    def allocate(self, request_id):
        if not self.free_blocks:
            raise RuntimeError("OOM: no free KV cache blocks")
        block_id = self.free_blocks.pop()
        self.block_tables.setdefault(request_id, []).append(block_id)
        return block_id

    def free(self, request_id):
        for block_id in self.block_tables.pop(request_id, []):
            self.free_blocks.append(block_id)
        print(f"  Freed blocks for request {request_id}. Free blocks: {len(self.free_blocks)}")


# ─────────────────────────────────────────────
# Demo
# ─────────────────────────────────────────────
if __name__ == "__main__":
    d_model, d_k = 32, 16
    model = TransformerWithKVCache(d_model, d_k)

    np.random.seed(1)
    prompt = np.random.randn(5, d_model)   # 5-token prompt

    print("=== Prefill Phase ===")
    first_output = model.prefill(prompt)

    print("\n=== Decode Phase ===")
    for step in range(3):
        new_token = np.random.randn(d_model)
        out = model.decode_one_token(new_token)

    print("\n=== PagedKVCache Demo ===")
    paged = PagedKVCache(block_size=4, num_blocks=8, d_k=d_k)
    paged.allocate("req_1")
    paged.allocate("req_1")
    paged.allocate("req_2")
    print(f"Free blocks after 3 allocations: {len(paged.free_blocks)}")
    paged.free("req_1")

"""
INTERVIEW TALKING POINTS:

Q: What problem does KV cache solve?
A: Without it, every decode step recomputes K,V for ALL past tokens — O(n^2)
   total FLOPs. With cache, we compute K,V once per token → O(n) total.

Q: What are the two phases of LLM inference?
A: Prefill: entire prompt processed in parallel (compute-bound, like training).
   Decode: one token at a time, reusing cache (memory-bandwidth-bound).

Q: What limits KV cache size?
A: GPU HBM. For a 70B model with 4096 context, KV cache alone is ~80 GB.
   This is why quantization (INT8/INT4 KV cache) is so valuable.

Q: What is PagedAttention?
A: Applies OS virtual memory concepts to KV cache. Divides cache into pages,
   allocates on demand. Eliminates fragmentation, enables prefix sharing.
   vLLM uses this to achieve near-theoretical GPU utilization.
"""
