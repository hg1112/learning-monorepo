"""
TOPIC: Attention Mechanism from Scratch
ByteDance Interview Prep — MLE Inference

Key interview questions:
  - Implement scaled dot-product attention
  - Why divide by sqrt(d_k)?
  - What is multi-head attention and why use it?
  - How does causal masking work?
  - What is the time/space complexity of attention?
"""

import numpy as np


# ─────────────────────────────────────────────
# 1. Scaled Dot-Product Attention
# ─────────────────────────────────────────────
def scaled_dot_product_attention(Q, K, V, mask=None):
    """
    Q: (batch, heads, seq_q, d_k)
    K: (batch, heads, seq_k, d_k)
    V: (batch, heads, seq_k, d_v)

    Complexity: O(n^2 * d) time, O(n^2) space for attention matrix
    """
    d_k = Q.shape[-1]

    # Scores: (batch, heads, seq_q, seq_k)
    scores = Q @ K.transpose(0, 1, 3, 2) / np.sqrt(d_k)

    # Causal mask: upper triangle = -inf so future tokens are invisible
    if mask is not None:
        scores = np.where(mask == 0, -1e9, scores)

    # Softmax over last dim (key positions)
    scores -= scores.max(axis=-1, keepdims=True)  # numerical stability
    weights = np.exp(scores) / np.exp(scores).sum(axis=-1, keepdims=True)

    # Weighted sum of values
    output = weights @ V  # (batch, heads, seq_q, d_v)
    return output, weights


# ─────────────────────────────────────────────
# 2. Multi-Head Attention
# ─────────────────────────────────────────────
class MultiHeadAttention:
    def __init__(self, d_model, num_heads):
        assert d_model % num_heads == 0
        self.d_model = d_model
        self.num_heads = num_heads
        self.d_k = d_model // num_heads

        # Weight matrices (randomly initialized here; trained in practice)
        scale = np.sqrt(2.0 / d_model)
        self.W_q = np.random.randn(d_model, d_model) * scale
        self.W_k = np.random.randn(d_model, d_model) * scale
        self.W_v = np.random.randn(d_model, d_model) * scale
        self.W_o = np.random.randn(d_model, d_model) * scale

    def split_heads(self, x):
        """(batch, seq, d_model) → (batch, heads, seq, d_k)"""
        batch, seq, _ = x.shape
        x = x.reshape(batch, seq, self.num_heads, self.d_k)
        return x.transpose(0, 2, 1, 3)

    def forward(self, x, mask=None):
        batch, seq, _ = x.shape

        Q = (x @ self.W_q).reshape(batch, seq, self.num_heads, self.d_k).transpose(0, 2, 1, 3)
        K = (x @ self.W_k).reshape(batch, seq, self.num_heads, self.d_k).transpose(0, 2, 1, 3)
        V = (x @ self.W_v).reshape(batch, seq, self.num_heads, self.d_k).transpose(0, 2, 1, 3)

        attn_out, weights = scaled_dot_product_attention(Q, K, V, mask)

        # Merge heads: (batch, seq, d_model)
        attn_out = attn_out.transpose(0, 2, 1, 3).reshape(batch, seq, self.d_model)

        return attn_out @ self.W_o, weights


# ─────────────────────────────────────────────
# 3. Causal Mask Generator
# ─────────────────────────────────────────────
def causal_mask(seq_len):
    """Lower-triangular mask: position i can only attend to positions <= i"""
    return np.tril(np.ones((seq_len, seq_len)))


# ─────────────────────────────────────────────
# Demo
# ─────────────────────────────────────────────
if __name__ == "__main__":
    np.random.seed(42)
    batch, seq, d_model, heads = 2, 8, 64, 8

    x = np.random.randn(batch, seq, d_model)
    mask = causal_mask(seq)[None, None, :, :]  # (1,1,seq,seq)

    mha = MultiHeadAttention(d_model, heads)
    out, weights = mha.forward(x, mask)

    print(f"Input:  {x.shape}")
    print(f"Output: {out.shape}")
    print(f"Attention weights: {weights.shape}")
    print(f"Weight row sum (should be ~1): {weights[0, 0, 0].sum():.4f}")

    # Verify causal: upper triangle of weights should be ~0
    upper_triangle_sum = np.triu(weights[0, 0], k=1).sum()
    print(f"Upper triangle attention mass (should be ~0): {upper_triangle_sum:.6f}")

"""
INTERVIEW TALKING POINTS:

Q: Why sqrt(d_k)?
A: Without scaling, dot products grow large as d_k increases, pushing softmax
   into saturation regions where gradients vanish. Dividing by sqrt(d_k)
   keeps variance at ~1 regardless of dimension.

Q: Why multi-head?
A: Each head can attend to different representation subspaces simultaneously.
   One head might capture syntax, another semantics, another positional patterns.

Q: Complexity?
A: Time O(n^2 * d), Space O(n^2) for the attention matrix.
   This is why long contexts are expensive — quadratic in sequence length.

Q: Flash Attention improvement?
A: Tiles the computation to avoid materializing the full n×n matrix in HBM.
   Same math, O(n) memory, much faster in practice (3-6x).
"""
