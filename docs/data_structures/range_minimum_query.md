# Range Minimum Query (RMQ)

Given an array, answer repeated queries of the form: **"what is the minimum value in `arr[l..r]`?"**

```
arr = [3, 1, 4, 1, 5, 9, 2, 6]
       0  1  2  3  4  5  6  7

RMQ(1, 5) = min(1, 4, 1, 5, 9) = 1   (index 1 or 3)
RMQ(4, 7) = min(5, 9, 2, 6)    = 2   (index 6)
RMQ(0, 0) = 3
```

Four implementations — pick based on whether the array is static or mutable:

| Approach | Preprocessing | Query | Space | Updates |
|---|---|---|---|---|
| Naive scan | O(1) | O(N) | O(1) | O(1) |
| Sparse Table | O(N log N) | **O(1)** | O(N log N) | No (static only) |
| Segment Tree | O(N) | O(log N) | O(N) | **O(log N)** |
| Block decomp (sqrt) | O(N) | O(√N) | O(N) | O(1) |

---

## Approach 1: Naive Scan

```java
int rmq(int[] arr, int l, int r) {
    int min = arr[l];
    for (int i = l + 1; i <= r; i++) min = Math.min(min, arr[i]);
    return min;
}
```

O(N) per query. Fine for one-off lookups. Unacceptable when Q queries are issued — O(N × Q) total.

---

## Approach 2: Sparse Table — O(1) Query, Static Array

### Concept

Precompute the minimum over every interval whose length is a power of 2.

```
arr =  [3,  1,  4,  1,  5,  9,  2,  6]
idx =   0   1   2   3   4   5   6   7

sparse[k][i] = minimum of arr[i .. i + 2^k - 1]

k=0 (length 1):  [3, 1, 4, 1, 5, 9, 2, 6]
k=1 (length 2):  [1, 1, 1, 1, 5, 2, 2]     e.g. sparse[1][0]=min(3,1)=1
k=2 (length 4):  [1, 1, 1, 1, 2, 2]         e.g. sparse[2][0]=min(3,1,4,1)=1
k=3 (length 8):  [1]                         e.g. sparse[3][0]=min(all)=1
```

### Query: Overlap Trick

Any query `[l, r]` can be covered by two overlapping power-of-2 windows — double-counting the overlap is fine because `min` is idempotent.

```
RMQ(l, r):
  k = floor(log2(r - l + 1))        // largest power-of-2 that fits in [l,r]
  return min(sparse[k][l],
             sparse[k][r - 2^k + 1])

Example: RMQ(2, 6), length = 5
  k = floor(log2(5)) = 2  →  window length = 4
  Left  window: sparse[2][2] = min(arr[2..5]) = min(4,1,5,9) = 1
  Right window: sparse[2][3] = min(arr[3..6]) = min(1,5,9,2) = 1
  Answer: min(1, 1) = 1  ✓

Overlap region arr[3..5] counted twice — safe because min(x, x) = x.
```

### Implementation

```java
class SparseTable {
    private final int[][] sparse;
    private final int[]   log2;
    private final int     n;

    SparseTable(int[] arr) {
        n = arr.length;
        int maxLog = Integer.numberOfTrailingZeros(Integer.highestOneBit(n)) + 1;

        // Precompute floor(log2(i)) for i = 1..n
        log2 = new int[n + 1];
        log2[1] = 0;
        for (int i = 2; i <= n; i++) log2[i] = log2[i / 2] + 1;

        // Build table: sparse[k][i] = min of arr[i .. i + 2^k - 1]
        sparse = new int[maxLog][n];
        sparse[0] = arr.clone();                          // k=0: single elements

        for (int k = 1; (1 << k) <= n; k++) {
            for (int i = 0; i + (1 << k) <= n; i++) {
                sparse[k][i] = Math.min(sparse[k - 1][i],
                                        sparse[k - 1][i + (1 << (k - 1))]);
            }
        }
    }

    // O(1) query — no loop, two array lookups
    int query(int l, int r) {
        int k = log2[r - l + 1];
        return Math.min(sparse[k][l],
                        sparse[k][r - (1 << k) + 1]);
    }
}
```

### Complexity

```
Preprocessing:  O(N log N) time,  O(N log N) space
Query:          O(1) — two array reads + one min()
Updates:        NOT supported — full rebuild required
```

**Use when:** array is static (read-only after build) and Q queries are large. Classic in competitive programming and LCA-via-Euler-tour problems.

---

## Approach 3: Segment Tree — O(log N) Query + Update

### Concept

Build a binary tree where each node stores the minimum of its subtree. The leaves are the array elements.

```
arr = [3, 1, 4, 1, 5, 9, 2, 6]

                    min=1 [0..7]
               /                  \
          min=1 [0..3]          min=2 [4..7]
          /        \             /         \
     min=1[0..1] min=1[2..3] min=5[4..5] min=2[6..7]
      /    \      /    \      /    \      /    \
     3      1    4      1    5      9    2      6
```

### Query

Walk down the tree, collecting nodes fully inside `[l, r]`. At most O(log N) nodes contribute.

```
RMQ(2, 6):  covers [2..3] fully, [4..5] fully, leaf [6]
            min(1, 5, 2) = 2  ← wrong, let me recalculate
            [2..3]=min(4,1)=1, [4..5]=min(5,9)=5, [6..6]=2
            min(1, 5, 2) = 1  ✓
```

### Implementation

```java
class SegmentTree {
    private final int[] tree;
    private final int   n;

    SegmentTree(int[] arr) {
        n = arr.length;
        tree = new int[4 * n];   // 4n is a safe upper bound
        build(arr, 1, 0, n - 1);
    }

    private void build(int[] arr, int node, int lo, int hi) {
        if (lo == hi) { tree[node] = arr[lo]; return; }
        int mid = (lo + hi) / 2;
        build(arr, 2 * node,     lo,      mid);
        build(arr, 2 * node + 1, mid + 1, hi);
        tree[node] = Math.min(tree[2 * node], tree[2 * node + 1]);
    }

    // Point update: set arr[idx] = val
    void update(int idx, int val) { update(1, 0, n - 1, idx, val); }

    private void update(int node, int lo, int hi, int idx, int val) {
        if (lo == hi) { tree[node] = val; return; }
        int mid = (lo + hi) / 2;
        if (idx <= mid) update(2 * node,     lo,      mid, idx, val);
        else            update(2 * node + 1, mid + 1, hi,  idx, val);
        tree[node] = Math.min(tree[2 * node], tree[2 * node + 1]);
    }

    // Range minimum query [l, r]
    int query(int l, int r) { return query(1, 0, n - 1, l, r); }

    private int query(int node, int lo, int hi, int l, int r) {
        if (r < lo || hi < l)  return Integer.MAX_VALUE;  // out of range
        if (l <= lo && hi <= r) return tree[node];         // fully inside
        int mid = (lo + hi) / 2;
        return Math.min(query(2 * node,     lo,      mid, l, r),
                        query(2 * node + 1, mid + 1, hi,  l, r));
    }
}
```

### Complexity

```
Build:    O(N)       — single pass bottom-up
Query:    O(log N)   — at most 4 nodes per level, log N levels
Update:   O(log N)   — walk one root-to-leaf path
Space:    O(N)       — 4N internal nodes (2N-1 actual used)
```

**Use when:** array is mutable (updates happen between queries). The standard choice for competitive programming and interview RMQ questions.

---

## Approach 4: Block Decomposition (√N)

Divide the array into blocks of size √N. Precompute the minimum of each block.

```
arr = [3, 1, 4, 1, 5, 9, 2, 6, 7, 3, 8, 2]   n=12, block=3

Blocks:  [3,1,4]  [1,5,9]  [2,6,7]  [3,8,2]
Block min:  1        1        2        2

Query RMQ(1, 9):
  Partial left block:   arr[1..2] = min(1, 4) = 1
  Full blocks:          block[1]=1, block[2]=2
  Partial right block:  arr[9..9] = 3
  Answer: min(1, 1, 2, 3) = 1  ✓
```

```java
class SqrtDecomposition {
    private final int[] arr, blockMin;
    private final int   blockSize;

    SqrtDecomposition(int[] arr) {
        this.arr = arr.clone();
        blockSize = (int) Math.sqrt(arr.length);
        blockMin  = new int[(arr.length + blockSize - 1) / blockSize];
        Arrays.fill(blockMin, Integer.MAX_VALUE);
        for (int i = 0; i < arr.length; i++)
            blockMin[i / blockSize] = Math.min(blockMin[i / blockSize], arr[i]);
    }

    void update(int idx, int val) {
        arr[idx] = val;
        int b = idx / blockSize;
        // Recompute block min from scratch (O(√N))
        blockMin[b] = Integer.MAX_VALUE;
        for (int i = b * blockSize; i < Math.min(arr.length, (b + 1) * blockSize); i++)
            blockMin[b] = Math.min(blockMin[b], arr[i]);
    }

    int query(int l, int r) {
        int min = Integer.MAX_VALUE;
        int lb = l / blockSize, rb = r / blockSize;
        if (lb == rb) {
            // Same block — scan directly
            for (int i = l; i <= r; i++) min = Math.min(min, arr[i]);
            return min;
        }
        // Left partial block
        for (int i = l; i < (lb + 1) * blockSize; i++) min = Math.min(min, arr[i]);
        // Full blocks
        for (int b = lb + 1; b < rb; b++) min = Math.min(min, blockMin[b]);
        // Right partial block
        for (int i = rb * blockSize; i <= r; i++) min = Math.min(min, arr[i]);
        return min;
    }
}
```

**Use when:** you want O(1) update with acceptable O(√N) query — simpler than a segment tree, useful when updates dominate.

---

## Complexity Comparison

```
                  Preprocess    Query     Update    Space
Naive             O(1)          O(N)      O(1)      O(1)
Sparse Table      O(N log N)    O(1)      ✗         O(N log N)
Segment Tree      O(N)          O(log N)  O(log N)  O(N)
Block (sqrt)      O(N)          O(√N)     O(√N)     O(N)

Sweet spot rules:
  Static array, max queries  →  Sparse Table   (O(1) query is hard to beat)
  Mutable array              →  Segment Tree   (balanced log N for both ops)
  Quick to code in interview →  Segment Tree   (one class, handles both)
  Extreme update frequency   →  Block decomp   (simpler update logic)
```

---

## Segment Tree Extensions

The same segment tree structure handles any associative operation over a range:

```
Range Sum:    tree[node] = tree[left] + tree[right]
Range Max:    tree[node] = Math.max(tree[left], tree[right])
Range GCD:    tree[node] = gcd(tree[left], tree[right])
Range AND/OR: tree[node] = tree[left] & tree[right]

Lazy propagation — range updates in O(log N):
  "Add 5 to every element in arr[2..7]"
  Without lazy: O(N) updates one by one
  With lazy:    tag the range node, push down only when the subtree is visited
```

---

## LeetCode Problems

| Problem | Approach | Why |
|---|---|---|
| 307 — Range Sum Query Mutable | Segment Tree | Point update + range sum |
| 303 — Range Sum Query Immutable | Prefix sum / Sparse Table | Static, sum not min |
| 239 — Sliding Window Maximum | Monotonic Deque | Fixed-size window, not arbitrary range |
| 218 — The Skyline Problem | Segment Tree / ordered map | Range max with events |
| 2276 — Count Integers in Intervals | Segment Tree (lazy) | Range merge + count |
| 315 — Count of Smaller Numbers After Self | Merge sort / BIT | Not RMQ but related |

---

## Key Rules

```
1.  Sparse Table for static arrays — O(1) query after O(N log N) build.
    Overlap trick: min(sparse[k][l], sparse[k][r - 2^k + 1]), k = floor(log2(len)).

2.  Segment Tree for mutable arrays — O(log N) both query and update.
    4N array is always a safe allocation size.

3.  The overlap trick only works for idempotent functions (min, max, gcd).
    For sum, use prefix sums (static) or BIT/segment tree (mutable).

4.  Segment tree indices: root=1, left child=2i, right child=2i+1.
    Leaf range [lo, hi] where lo==hi stores the element directly.

5.  Query base cases: out-of-range → identity (MAX_VALUE for min, 0 for sum);
    fully-inside → return node value immediately.

6.  For range updates (not just point updates), add lazy propagation.
    Store a pending delta/tag at each node; push down before recursing into children.
```
