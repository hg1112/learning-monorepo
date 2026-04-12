# Painting a Grid With Three Different Colors

[LeetCode 1931](https://leetcode.com/problems/painting-a-grid-with-three-different-colors/)

---

## Problem

Given an `m × n` grid, paint each cell with one of **3 colors** such that no two
adjacent cells (horizontally or vertically) share the same color.
Return the number of valid colorings modulo `10^9 + 7`.

**Constraints:** `1 ≤ m ≤ 5`, `1 ≤ n ≤ 1000`

---

## Why the Naive Approach Fails

### The broken idea: cell-by-cell DFS with `dp[r][c][clr]`

A natural first attempt is to start at one corner, recurse into adjacent
unvisited cells, and memoize with the key `(row, col, color)`.

```
visited[m-1][n-1] = 1
dp[0][0][i] = 1  for all i        ← base case: top-left can be any color
ans = f(m-1, n-1, color=0) * 3   ← start at bottom-right, multiply by 3
```

This has **three bugs**.

---

### Bug 1 — Memoization and backtracking are mutually exclusive

`dp[r][c][clr]` is stored the first time `f(r, c, clr)` is called.
But the correct answer for that state also depends on which cells are already
colored — information carried by the `visited[][]` array that is NOT part of the
cache key.

A subsequent call to `f(r, c, clr)` with a different `visited` state returns the
wrong cached value.

**Analogy:** Imagine caching "number of ways to complete a maze from room X"
without recording which other rooms are already blocked. The same room can have
vastly different answers depending on what's blocked.

---

### Bug 2 — DFS does not color all cells

The 4-direction DFS from `(m-1, n-1)` follows adjacency edges through unvisited
cells. It can reach cell `(0, 0)` (the pre-seeded base case) after visiting only
a **path** — a subset of cells — leaving the rest of the grid uncolored.

**Trace for m=2, n=2:**

```
(0,0)  (0,1)
(1,0)  (1,1) ← start here, color 0
```

DFS path: `(1,1) → (0,1) → (0,0)`.
Cell `(1,0)` is never visited. Yet the function returns as if the whole grid is
colored.

---

### Bug 3 — `ans * 3` is wrong in general

The code calls `f(m-1, n-1, color=0)` and then multiplies by 3, hoping that
each of the 3 starting colors produces the same count. This only holds for `m=1`
due to full color symmetry. For `m > 1` the grid topology breaks that symmetry.

**m=2, n=2 — actual count by starting color at (1,1):**

```
color 0 at (1,1)  →  8 paths counted (wrong; cell (1,0) uncolored)
code returns 8 * 3 = 24,  correct answer is 18
```

---

### Root cause

`dp[r][c][clr]` is **unsound** for a 2D grid.

The count of valid completions at `(r, c)` with color `clr` depends on the colors
of **both** the left neighbor and the above neighbor. A single-cell key cannot
capture this without knowing the full history of how you arrived there.

---

## The Fix — Column Patterns

### Key insight

Since `m ≤ 5`, a single column has at most `3 × 2^(m-1) = 48` valid colorings
(where vertically adjacent cells differ). Treat each column as one unit — a
**pattern** — and reduce the 2D problem to a 1D one.

```
m=1:  3 patterns      (0), (1), (2)
m=2:  6 patterns      (0,1), (0,2), (1,0), (1,2), (2,0), (2,1)
m=3: 12 patterns      (0,1,0), (0,1,2), (0,2,0), (0,2,1), ...
m=5: 48 patterns
```

Two adjacent columns are **compatible** if **no row has the same color in both**.
This single pairwise check encodes the entire horizontal adjacency constraint.

---

## Recursive Equation

```
f(col, p)  =  Σ f(col+1, q)    for all q compatible with p

f(n-1, p)  =  1                 (last column: pattern already fixed)

answer     =  Σ f(0, p)         over all valid patterns p
```

`f(col, p)` = number of ways to color columns `col` through `n-1`
              given column `col` uses pattern `p`.

### Why memoization is now sound

`f(col, p)` depends **only** on `(col, p)`. There is no hidden state. The valid
completions from any `(col, p)` are always identical regardless of how earlier
columns were colored.

---

## Worked Examples

### Example 1 — m=1, n=1

```
Grid: [?]

Valid patterns for m=1: {0}, {1}, {2}   →   3 patterns
n=1 means there is exactly one column.
f(0, p) = 1 for each p.
Answer = 3
```

### Example 2 — m=1, n=3

```
Grid: [?][?][?]

For any column pattern p, compatible patterns = the other 2.
f(2, p) = 1
f(1, p) = 2    (2 compatible choices for column 2)
f(0, p) = 4    (each of 2 compatible columns feeds 2 more)
Answer = 3 patterns × 4 = 12
```

### Example 3 — m=2, n=2

```
Grid:
  (0,0)(0,1)
  (1,0)(1,1)

Valid patterns for m=2:
  p0=(0,1)  p1=(0,2)  p2=(1,0)  p3=(1,2)  p4=(2,0)  p5=(2,1)

Compatibility for p0=(0,1):
  Next column needs: row0 ≠ 0  AND  row1 ≠ 1
  row0 options: {1, 2},  row1 options: {0, 2}
  Valid: (1,0)✓  (1,2)✓  (2,0)✓  (2,1)? row1=1==1 ✗
  Compatible with p0: {p2, p3, p4}  →  3 patterns

By symmetry each pattern has exactly 3 compatible patterns.

f(1, any) = 1
f(0, p)   = 3
Answer    = 6 × 3 = 18  ✓
```

### Example 4 — m=2, n=3

```
f(2, q) = 1
f(1, p) = Σ f(2,q) for compatible q = 3
f(0, p) = Σ f(1,q) for compatible q = 3 × 3 = 9
Answer  = 6 × 9 = 54  ✓
```

---

## Edge Cases

| Case | Why it matters |
|------|---------------|
| `n=1` | Only column 0 exists. Answer = number of valid patterns = `3 × 2^(m-1)`. |
| `m=1` | Every pattern is a single cell. All 3 are always mutually compatible. Answer = `3 × 2^(n-1)`. |
| `m=5, n=1000` | Maximum constraint. 48 patterns, ~2.3M operations — well within limits. |
| Large `n` | Modular arithmetic on every addition prevents overflow. Never multiply two `long` values before taking mod. |

### n=1 sanity check

With a single column, `f(0, p) = 1` for all `p`.
Answer = number of valid patterns for `m` rows = `3 × 2^(m-1)`.

```
m=1 → 3    m=2 → 6    m=3 → 12    m=4 → 24    m=5 → 48
```

---

## Complexity

|                 | Bad solution          | Good (recursive)     | Optimal (iterative)  |
|-----------------|-----------------------|----------------------|----------------------|
| Correctness     | Wrong for m > 1       | Correct              | Correct              |
| Time            | Undefined / wrong     | O(n × P²)            | O(n × P²)            |
| Space           | O(m × n × 3) + stack  | O(n × P) memo        | O(P) rolling array   |
| P (max m=5)     | —                     | 48                   | 48                   |
| Ops (n=1000)    | —                     | ~2.3M                | ~2.3M                |

---

## Solution Comparison

### Bad — cell-by-cell DFS (`SolutionBad.java`)

```
State key : (row, col, color)         ← missing neighbor-color info
Traversal : 4-direction DFS           ← skips cells
Base case : dp[0][0][i] = 1          ← wrong semantics
Answer    : f(m-1,n-1,0) × 3         ← wrong multiplier
```

### Good — column-pattern memoized recursion (`Solution.java`)

```
State key : (col, patternIndex)       ← sound: fully determines future
Traversal : left-to-right columns     ← covers every column exactly once
Base case : col == n-1  →  return 1  ← correct: pattern already chosen
Answer    : Σ f(0, p) over all p     ← sums over all column 0 choices
```

### Optimal — iterative rolling DP (`SolutionOptimal.java`)

```
Same algorithm as Good, but iterative.
dp[p] accumulates count for current column pattern p.
Propagate forward: for each column, push dp[i] into dp[j] for compatible j.
No recursion stack, O(P) space.
```
