# Union-Find (Disjoint Set Union)

A data structure that tracks elements partitioned into disjoint (non-overlapping) sets.
Supports two operations in near-constant amortized time:
- **`find(x)`** — return the representative (root) of x's set
- **`union(x, y)`** — merge the sets containing x and y

---

## Core Concept

### Visual Representation

Elements start as their own parents. We merge them by pointing roots together.

```text
Initial:  {0}  {1}  {2}  {3}  {4}
parent:   [0,   1,   2,   3,   4]

union(0, 1):  {0,1}  {2}  {3}  {4}
parent:   [1,   1,   2,   3,   4]   (0 -> 1)

union(2, 3):  {0,1}  {2,3}  {4}
parent:   [1,   1,   3,   3,   4]   (2 -> 3)

union(1, 3):  {0,1,2,3}  {4}
parent:   [1,   3,   3,   3,   4]   (1 -> 3, sets merged)
```

---

## Optimizations

### 1. Union by Rank
Always attach the shorter tree under the taller one. Keeps height O(log N).

```text
Before union(A-tree, B-tree):     After (B-tree is taller):
     Root-A (rank 1)                    Root-B (rank 2)
       |                               /        \
       C                           Root-A       ...
                                     |
                                     C
```

### 2. Path Compression
After `find(x)`, point every node along the path directly to the root.
Combined with union by rank: amortized O(α(N)) per operation.

```text
Before find(5):         After find(5):
      [ 1 ]                   [ 1 ]
       /                    / | \
     [ 2 ]                [2][3][5]
     /
   [ 3 ]
   /
 [ 5 ]
```

---

## Dense Union-Find (Array-backed)

Use when elements are indexed 0..N-1 and the total count is manageable.

```java
class UnionFind {
    private int[] parent, rank;
    private int components;

    public UnionFind(int n) {
        parent = new int[n];
        rank   = new int[n];
        components = n;
        for (int i = 0; i < n; i++) parent[i] = i;
    }

    public int find(int x) {
        if (parent[x] != x)
            parent[x] = find(parent[x]);  // path compression
        return parent[x];
    }

    public boolean union(int x, int y) {
        int px = find(x), py = find(y);
        if (px == py) return false;        // already same set
        if (rank[px] < rank[py]) { int t = px; px = py; py = t; }
        parent[py] = px;
        if (rank[px] == rank[py]) rank[px]++;
        components--;
        return true;
    }

    public int components() { return components; }
}
```

### Complexity (Dense)

| Operation | Time | Space |
|-----------|------|-------|
| `find` | O(α(N)) amortized | — |
| `union` | O(α(N)) amortized | — |
| Total space | — | O(N) |

α = Inverse Ackermann function; effectively O(1) for any practical N.

---

## Sparse Union-Find (HashMap-backed)

Use when the coordinate space is massive (e.g., 10,000 × 10,000 grid = 10^8 cells)
but only a small number of cells L are actually active.

### Why Dense Arrays Fail for Large Grids

For m=n=10,000:
- `parent[m*n]` → 400 MB
- `rank[m*n]`   → 400 MB
- Total → exceeds 256–512 MB heap limits

### Sparse Strategy

Store only active cells in a `Map<Long, Long> parent`.

**Coordinate encoding:** convert `(r, c)` → unique `long` via `(long) r * n + c`.

```java
class SparseUnionFind {
    private Map<Long, Long> parent = new HashMap<>();
    private Map<Long, Integer> rank = new HashMap<>();
    private int components = 0;

    /** Activate a new cell (call once per cell, on first land addition). */
    public void add(long id) {
        if (parent.containsKey(id)) return;  // already active (duplicate position)
        parent.put(id, id);
        rank.put(id, 0);
        components++;
    }

    public boolean exists(long id) {
        return parent.containsKey(id);
    }

    /** Iterative find with two-pass path compression (stack-safe). */
    public long find(long id) {
        long root = id;
        while (parent.get(root) != root) root = parent.get(root);
        // path compression: point all nodes on path directly to root
        while (id != root) {
            long next = parent.get(id);
            parent.put(id, root);
            id = next;
        }
        return root;
    }

    /** Returns true if a new union was made (two islands merged). */
    public boolean union(long x, long y) {
        long px = find(x), py = find(y);
        if (px == py) return false;
        int rx = rank.get(px), ry = rank.get(py);
        if (rx < ry) { long t = px; px = py; py = t; }
        parent.put(py, px);
        if (rx == ry) rank.put(px, rx + 1);
        components--;
        return true;
    }

    public int components() { return components; }
}
```

### Why iterative find?
Recursive `find` can hit `StackOverflowError` on a long chain (rare with rank compression,
but possible before the first pass flattens it). Iterative two-pass is unconditionally safe.

---

## Worked Example — Number of Islands II

**LeetCode 305** | [Solution.java](../../challenges/leetcode/NumberOfIslandsII/Solution.java)

**Problem:** Start with an m×n water grid. Process L operations that flip a cell to land.
After each operation, return the current island count.

**Approach:** Sparse Union-Find with coordinate encoding.

```
Grid state after each operation (3×3, positions: (0,0),(0,1),(1,2),(1,1)):

Step 1: add (0,0)         → 1 island
        [1, 0, 0]
        [0, 0, 0]
        [0, 0, 0]

Step 2: add (0,1)         → union((0,0),(0,1)) → 1 island
        [1, 1, 0]
        [0, 0, 0]
        [0, 0, 0]

Step 3: add (1,2)         → no neighbors → 2 islands
        [1, 1, 0]
        [0, 0, 1]
        [0, 0, 0]

Step 4: add (1,1)         → union with (0,1) and (1,2) → 1 island
        [1, 1, 0]
        [0, 1, 1]
        [0, 0, 0]
```

**Key pitfall:** Duplicate positions in the input (same cell added twice).
Check `uf.exists(id)` before calling `uf.add(id)` to avoid double-counting.

**Complexity:**

| Aspect | Complexity | Notes |
|--------|-----------|-------|
| Time | O(L · α(L)) | L = # of positions |
| Space | O(L) | Independent of grid dimensions M × N |

---

## Coordinate Compression (Variant for Extreme Grids)

If the grid is 10^9 × 10^9 but operations are few:
1. Collect all unique points from `positions` (up to L distinct points)
2. Map each to index 0..L-1
3. Use dense `int[]` arrays for parent/rank (faster than HashMap; no boxing)

Trade-off: requires preprocessing all positions upfront (offline), whereas HashMap UF can process positions one at a time (online).

---

## Complexity Summary

| Variant | Time per op | Space | Best for |
|---------|------------|-------|---------|
| Dense array | O(α(N)) | O(N) | N ≤ 10^6, cells indexed 0..N-1 |
| Sparse HashMap | O(α(L)) | O(L) | Huge coordinate space, few active cells |
| Coord-compressed dense | O(α(L)) | O(L) | Same as sparse, but offline + faster |

---

## Common Patterns

### Connected Components Count
Start with `components = N`. Each successful `union` decrements it.

### Cycle Detection in Undirected Graph
If `find(u) == find(v)` before `union(u,v)`, edge `u-v` creates a cycle.

### Online vs Offline
- **Online (streaming):** Sparse HashMap UF — add cells as they arrive.
- **Offline (all input known):** Coordinate-compress, then dense UF.
