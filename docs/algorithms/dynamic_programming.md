# Dynamic Programming (DP)

An optimization technique that solves complex problems by breaking them into
overlapping subproblems and storing results to avoid recomputation.

---

## Core Requirements

1. **Optimal Substructure:** The optimal solution to the problem can be built from
   optimal solutions to its subproblems.
2. **Overlapping Subproblems:** The same subproblems are solved multiple times.
   DP caches them (memoization) or builds them bottom-up (tabulation).

---

## Two Styles

| Style | Approach | When to use |
|-------|----------|------------|
| Top-down (Memoization) | Recursive + cache | Natural recursion, partial state space |
| Bottom-up (Tabulation) | Iterative table | Full state space, better constant factors |

---

## Classic Example — 0/1 Knapsack

**Problem:** Bag of capacity W. Items with weights and values. Maximize total value.

**Recurrence:**
```
dp[i][j] = max(
    dp[i-1][j],                         // exclude item i
    val[i] + dp[i-1][j - weight[i]]     // include item i (if weight[i] <= j)
)
```

### Tabulation Trace

Items: (W:2,V:3), (W:3,V:4), (W:4,V:5). Capacity: 5.

```text
Items \ Cap | 0 | 1 | 2 | 3 | 4 | 5 |
------------|---|---|---|---|---|---|
None        | 0 | 0 | 0 | 0 | 0 | 0 |
Item1(W2,V3)| 0 | 0 | 3 | 3 | 3 | 3 |
Item2(W3,V4)| 0 | 0 | 3 | 4 | 4 | 7 | ← max(3, 4+dp[item1][2]) = 7
Item3(W4,V5)| 0 | 0 | 3 | 4 | 5 | 7 |
```

### Java Implementation

**Top-down (Memoization):**
```java
int[][] memo;
public int knapsack(int[] wt, int[] val, int W, int n) {
    memo = new int[n+1][W+1];
    for (int[] row : memo) Arrays.fill(row, -1);
    return solve(wt, val, W, n);
}
private int solve(int[] wt, int[] val, int w, int n) {
    if (n == 0 || w == 0) return 0;
    if (memo[n][w] != -1) return memo[n][w];
    if (wt[n-1] > w) return memo[n][w] = solve(wt, val, w, n-1);
    return memo[n][w] = Math.max(
        solve(wt, val, w, n-1),
        val[n-1] + solve(wt, val, w - wt[n-1], n-1)
    );
}
```

**Bottom-up (Tabulation):**
```java
public int knapsack(int[] wt, int[] val, int W, int n) {
    int[][] dp = new int[n+1][W+1];
    for (int i = 1; i <= n; i++)
        for (int j = 1; j <= W; j++)
            dp[i][j] = wt[i-1] <= j
                ? Math.max(dp[i-1][j], val[i-1] + dp[i-1][j - wt[i-1]])
                : dp[i-1][j];
    return dp[n][W];
}
// Space-optimized: O(W) using 1D array (iterate j from W down to wt[i-1])
```

**Complexity:** Time O(N×W), Space O(N×W) → optimizable to O(W).

---

## When Memoization Is NOT Sound

Memoization is only valid when the state key **fully determines** the future answers.

**Counterexample — cell-by-cell DFS on a grid:**

```
dp[r][c][color] = ways to complete the grid given cell (r,c) is colored `color`
```

This is **unsound** because the answer at `(r,c,color)` also depends on which other
cells are already colored — information not captured in the key.

```
f(r,c,clr) called with visited={A,B,C}  → cached result R
f(r,c,clr) called with visited={A,B,D}  → returns same R  ← WRONG
```

**Rule:** before memoizing, ask "does my state key capture all information that
affects future choices?" If the answer is no, the memo is invalid.

See the full bug analysis in the PaintingGrid worked example below.

---

## Column-Pattern DP

A technique for grid-coloring and tiling problems where `m` is small.
Instead of tracking individual cells, treat each **column as one unit** (a pattern).
Reduces 2D state to 1D.

**Key insight:** `m ≤ 5` → at most 3 × 2^(m-1) = 48 valid column colorings.

### Worked Example — Painting a Grid With Three Colors

**LeetCode 1931** | [Solution.java](../../challenges/leetcode/PaintingAGridWithThreeColors/Solution.java)

**Problem:** m×n grid, 3 colors, no two adjacent cells (H or V) same color. Count valid colorings mod 10^9+7.

#### Why Cell-by-Cell DFS Fails (3 Bugs)

**Bug 1 — Memoization + backtracking are mutually exclusive:**
```
dp[r][c][clr] is cached the first time f(r,c,clr) is called.
But f(r,c,clr) also depends on visited[][] — not in the key.
Second call with a different visited state returns wrong cached value.
```

**Bug 2 — DFS does not color all cells:**
```
DFS from (m-1,n-1) follows adjacency edges. It visits a path, not all cells.
Example m=2,n=2: path (1,1)→(0,1)→(0,0) skips (1,0) entirely.
```

**Bug 3 — `ans * 3` wrong for m > 1:**
```
Starting at (1,1) with color 0 gives 8 paths (with bug 2).
Code returns 8×3=24. Correct answer is 18.
The multiplier of 3 only holds for m=1 (full color symmetry).
For m>1, different starting colors yield different counts.
```

**Root cause:** `dp[r][c][clr]` cannot encode the dependency on both the left and above neighbor.

#### The Fix — Column Patterns

Since `m ≤ 5`, enumerate all valid column patterns (adjacent rows differ):
```
m=1: 3 patterns      {0},{1},{2}
m=2: 6 patterns      {01},{02},{10},{12},{20},{21}
m=3: 12 patterns     {010},{012},{020},{021},{012}...
m=5: 48 patterns     3 × 2^(m-1)
```

Two adjacent columns are **compatible** if no row has the same color in both.

**Recurrence:**
```
f(col, p) = Σ f(col+1, q)   for all q compatible with p
f(n-1, p) = 1               (last column, already placed)
answer    = Σ f(0, p)        over all valid patterns p
```

**Why memoization is now sound:**
`f(col, p)` depends **only** on (col, p). No hidden state. Any path to (col, p)
yields the same future count regardless of how earlier columns were chosen.

#### Optimal: Iterative Rolling DP

```java
// Pre-compute: valid patterns + compatibility matrix
// dp[p] = count of ways for current column using pattern p
long[] dp = new long[P];    // P = number of valid patterns
Arrays.fill(dp, 1);          // base: column n-1, each pattern has 1 way

for (int col = n - 2; col >= 0; col--) {
    long[] next = new long[P];
    for (int i = 0; i < P; i++)
        for (int j : compatible[i])   // pre-computed compatibility
            next[j] = (next[j] + dp[i]) % MOD;
    dp = next;
}
long ans = 0;
for (long x : dp) ans = (ans + x) % MOD;
```

Space: O(P) — rolling array, no recursion stack.

#### Complexity

| Approach | Correctness | Time | Space |
|----------|-------------|------|-------|
| Cell-by-cell DFS | Wrong for m>1 | — | — |
| Column-pattern memoized | Correct | O(n×P²) | O(n×P) |
| Column-pattern iterative | Correct | O(n×P²) | O(P) |

P=48 (max, m=5). For n=1000: ~2.3M operations. Well within limits.

#### Worked Examples

```
m=1, n=1:  3 patterns × 1 = 3
m=1, n=3:  3 × 4 = 12   (each of 3 patterns → 2 compatible → 2 more)
m=2, n=2:  6 patterns, each has 3 compatible → 6 × 3 = 18  ✓
m=2, n=3:  6 × 9 = 54  ✓  (each f(1,p)=3, so f(0,p)=3×3=9)
```

---

## Common DP Patterns

### 1D — Fibonacci-style
```java
dp[i] = dp[i-1] + dp[i-2];  // Climbing Stairs, Fibonacci
```

### 1D — Running Optimization
```java
dp[i] = max(dp[i-1] + a[i], a[i]);  // Kadane's (max subarray)
```

### 2D — Grid Paths
```java
dp[r][c] = dp[r-1][c] + dp[r][c-1];  // Unique Paths
```

### 2D — Interval DP
```java
for (int len = 2; len <= n; len++)
    for (int i = 0; i + len - 1 < n; i++) {
        int j = i + len - 1;
        for (int k = i; k < j; k++)
            dp[i][j] = max(dp[i][j], dp[i][k] + dp[k+1][j] + cost(i,k,j));
    }
// Burst Balloons, Matrix Chain Multiplication
```

### DP on Strings
```java
// Longest Common Subsequence
if (s1[i] == s2[j]) dp[i][j] = dp[i-1][j-1] + 1;
else                 dp[i][j] = max(dp[i-1][j], dp[i][j-1]);
```

---

## Complexity Summary

| Problem type | Time | Space | Optimization |
|-------------|------|-------|-------------|
| 0/1 Knapsack | O(N×W) | O(N×W) | O(W) with rolling row |
| LCS / Edit Distance | O(M×N) | O(M×N) | O(N) rolling |
| Column-pattern DP | O(n×P²) | O(n×P) | O(P) rolling |
| Interval DP | O(N³) | O(N²) | — |
| DP on subsets (bitmask) | O(2^N × N) | O(2^N) | — |
