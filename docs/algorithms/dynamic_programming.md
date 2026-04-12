# Dynamic Programming (DP)

An optimization technique that solves complex problems by breaking them down into simpler, overlapping subproblems.

### Two Main Approaches
1.  **Top-Down (Memoization)**: Recursive + Map/Array to store results.
2.  **Bottom-Up (Tabulation)**: Iterative + Table to build results.

---

### Visual Representation (Fibonacci Tree)
In plain recursion, we recompute the same values many times.

```text
Recursive Call Tree for fib(5):
       fib(5)
      /      \
   fib(4)    fib(3)
   /   \      /   \
fib(3) fib(2) fib(2) fib(1)
  ^      ^      ^
  RECOMPUTED MANY TIMES!
```

**DP Solution:** We store the result of `fib(3)` the first time we find it and reuse it later.

---

### Tabulation (Bottom-Up)
We build a table from the smallest subproblems up to the final answer.

```text
i:      0   1   2   3   4   5
dp[i]: [0] [1] [1] [2] [3] [5]
                ^   ^   ^
                |   |   dp[4] + dp[3] = 5
                dp[1] + dp[0] = 1
```

### Key Use Cases
- Shortest path (Bellman-Ford)
- Knapsack Problems
- Longest Common Subsequence
- Min/Max cost problems (like Split Array Largest Sum)

### Complexity
- **Time**: $O(\text{Number of unique subproblems} \times \text{Cost per transition})$
- **Space**: $O(\text{Number of unique subproblems})$
