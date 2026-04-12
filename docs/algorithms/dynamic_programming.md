# Dynamic Programming (DP)

An optimization technique that solves complex problems by breaking them down into simpler, overlapping subproblems.

### Core Requirements
1.  **Optimal Substructure**: The solution to a larger problem can be built from the optimal solutions of its subproblems.
2.  **Overlapping Subproblems**: The same subproblems are solved multiple times during the computation.

---

## 🎒 Complex Example: 0/1 Knapsack Problem

**Problem:** You have a bag with a capacity $W$. You have items with specific weights and values. What is the maximum value you can carry?

### 1. Optimal Substructure
To decide the best value for $i$ items and capacity $j$, we have two choices:
1.  **Exclude Item $i$**: The value is the same as the best we did for $i-1$ items with the same capacity $j$.
2.  **Include Item $i$**: (If weight $w_i \le j$) The value is item $i$'s value plus the best we did for $i-1$ items with capacity $j - w_i$.

**The Recurrence Relation:**
$$dp[i][j] = \max(dp[i-1][j], \text{val}[i] + dp[i-1][j - \text{weight}[i]])$$

---

### 2. Overlapping Subproblems
In a simple recursive approach, the same `(index, remaining_capacity)` state is calculated many times.

```text
Recursive Call Tree:
          solve(i=3, cap=10)
          /                \
   solve(2, 10)         solve(2, 7)  <-- (if item 3 weight=3)
    /        \           /        \
solve(1, 10) solve(1, 8) solve(1, 7) solve(1, 5)
...
```
As the tree grows, different branches eventually ask for the same `solve(index, capacity)` values. **Memoization** saves these results in a table to ensure each state is computed only once.

---

### Visualizing the Tabulation (Bottom-Up)

Imagine items: (W:2, V:3), (W:3, V:4), (W:4, V:5). Max Capacity: 5.

```text
Items \ Cap | 0 | 1 | 2 | 3 | 4 | 5 |
------------|---|---|---|---|---|---|
None (0)    | 0 | 0 | 0 | 0 | 0 | 0 |
Item 1 (W2) | 0 | 0 | 3 | 3 | 3 | 3 |
Item 2 (W3) | 0 | 0 | 3 | 4 | 4 | 7 | <-- Max(3, 4 + dp[item1][5-3=2]) = 7
Item 3 (W4) | 0 | 0 | 3 | 4 | 5 | 7 |
```

---

### Java Implementation

#### Top-Down (Memoization)
```java
public int knapsack(int[] wt, int[] val, int W, int n) {
    int[][] memo = new int[n + 1][W + 1];
    for (int[] row : memo) Arrays.fill(row, -1);
    return solve(wt, val, W, n, memo);
}

private int solve(int[] wt, int[] val, int w, int n, int[][] memo) {
    if (n == 0 || w == 0) return 0;
    if (memo[n][w] != -1) return memo[n][w];

    if (wt[n-1] > w) {
        return memo[n][w] = solve(wt, val, w, n - 1, memo);
    } else {
        int exclude = solve(wt, val, w, n - 1, memo);
        int include = val[n-1] + solve(wt, val, w - wt[n-1], n - 1, memo);
        return memo[n][w] = Math.max(exclude, include);
    }
}
```

#### Bottom-Up (Tabulation)
```java
public int knapsack(int[] wt, int[] val, int W, int n) {
    int[][] dp = new int[n + 1][W + 1];

    for (int i = 1; i <= n; i++) {
        for (int j = 1; j <= W; j++) {
            if (wt[i-1] <= j) {
                dp[i][j] = Math.max(dp[i-1][j], val[i-1] + dp[i-1][j - wt[i-1]]);
            } else {
                dp[i][j] = dp[i-1][j];
            }
        }
    }
    return dp[n][W];
}
```

### Complexity
- **Time**: $O(N \cdot W)$ — We compute each state exactly once.
- **Space**: $O(N \cdot W)$ for the table (can be optimized to $O(W)$ using 1D array).
