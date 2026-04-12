# Depth-First Search (DFS)

An algorithm for traversing or searching tree or graph data structures by exploring
as far as possible along each branch before backtracking. Uses the call stack (recursive)
or an explicit stack (iterative).

---

## Visual — Deep First

```text
Level 0:          ( A )
                   / \
Level 1:        ( B ) ( E )
                 / \     \
Level 2:      ( C )( D ) ( F )

Traversal order: A → B → C → D → E → F
(visit deepest nodes before siblings)
```

---

## Recursive Implementation (Standard)

```java
public void dfsRecursive(int node, List<List<Integer>> adj, boolean[] visited) {
    visited[node] = true;
    // process node here

    for (int neighbor : adj.get(node)) {
        if (!visited[neighbor]) {
            dfsRecursive(neighbor, adj, visited);
        }
    }
}
```

---

## Iterative Implementation

Use `ArrayDeque` as a stack (not `Stack<E>` — it's legacy and synchronized).

```java
public void dfsIterative(int start, List<List<Integer>> adj, int n) {
    boolean[] visited = new boolean[n];
    Deque<Integer> stack = new ArrayDeque<>();
    stack.push(start);

    while (!stack.isEmpty()) {
        int node = stack.pop();
        if (visited[node]) continue;
        visited[node] = true;
        // process node here

        for (int neighbor : adj.get(node)) {
            if (!visited[neighbor]) {
                stack.push(neighbor);
            }
        }
    }
}
```

**Note:** Iterative DFS visits neighbors in reverse order compared to recursive DFS
(because the stack is LIFO). For exact same order, push neighbors in reverse.

---

## DFS + Memoization

When DFS explores a state space where the same state can be reached multiple times,
add a cache (memoization) to avoid recomputation.

### Template

```java
Map<StateKey, Answer> memo = new HashMap<>();

public Answer dfs(State s) {
    if (isBaseCase(s)) return baseAnswer(s);
    if (memo.containsKey(s.key())) return memo.get(s.key());

    Answer ans = /* combine results of recursive calls */;
    memo.put(s.key(), ans);
    return ans;
}
```

### When Is the State Key Sound?

**Memoization is valid only when the state key fully determines all future outcomes.**

```
VALID:   key = (x, y) where x,y are the only inputs affecting future choices
INVALID: key = (r, c, color) when a visited[][] array also affects the answer
         → the same (r,c,color) yields different results depending on visited state
```

See `dynamic_programming.md` — Painting Grid section for a detailed 3-bug analysis
of why `dp[r][c][color]` memoization fails for 2D grid coloring.

### Example — Minimum Knight Moves (DFS + Memo)

```java
Map<String, Integer> memo = new HashMap<>();

public int minKnightMoves(int x, int y) {
    x = Math.abs(x); y = Math.abs(y);
    if (x < y) { int t = x; x = y; y = t; }  // canonical: x >= y
    return dfs(x, y);
}

private int dfs(int x, int y) {
    if (x == 0 && y == 0) return 0;
    if (x + y == 2) return 2;           // base case: (2,0),(0,2),(1,1) all need 2 moves
    String key = x + "," + y;
    if (memo.containsKey(key)) return memo.get(key);
    int res = 1 + Math.min(
        dfs(Math.abs(x-1), Math.abs(y-2)),
        dfs(Math.abs(x-2), Math.abs(y-1))
    );
    memo.put(key, res);
    return res;
}
```

The `x+y==2` base case encodes the 2-step path through a negative coordinate
(e.g., `(0,0)→(2,−1)→(1,1)`). Without it, `f(1,0)→f(0,2)→f(2,0)→f(1,0)` cycles.
See `docs/algorithms/bfs.md` for the full knight moves analysis.

---

## DFS on Grids

```java
int[] dr = {0, 0, 1, -1};
int[] dc = {1, -1, 0, 0};

public void dfs(int[][] grid, int r, int c, boolean[][] visited) {
    if (r < 0 || r >= grid.length || c < 0 || c >= grid[0].length) return;
    if (visited[r][c] || grid[r][c] == 0) return;
    visited[r][c] = true;
    for (int d = 0; d < 4; d++)
        dfs(grid, r + dr[d], c + dc[d], visited);
}
```

**Stack overflow risk:** For large grids (m×n ~ 10^6), recursive DFS can overflow.
Switch to iterative or increase stack size with a thread:
```java
new Thread(null, this::solution, "dfs", 1 << 26).start();
```

---

## DFS for Cycle Detection

**Undirected graph:** cycle if we revisit a node that isn't the direct parent.
```java
boolean hasCycle(int u, int parent, List<List<Integer>> adj, boolean[] visited) {
    visited[u] = true;
    for (int v : adj.get(u)) {
        if (!visited[v]) {
            if (hasCycle(v, u, adj, visited)) return true;
        } else if (v != parent) return true;  // back edge
    }
    return false;
}
```

**Directed graph:** use 3-color marking (WHITE=0, GRAY=1 in-progress, BLACK=2 done).
A GRAY neighbor during DFS = back edge = cycle.

---

## Complexity

| | Time | Space |
|-|------|-------|
| DFS (graph) | O(V+E) | O(V) — call stack depth |
| DFS (grid m×n) | O(m×n) | O(m×n) |
| DFS + memo (state space S) | O(S) | O(S) |

---

## BFS vs DFS — When to Choose

| Need | Use |
|------|-----|
| Shortest path (unweighted) | BFS |
| Detect cycles in directed graph | DFS (3-color) |
| Topological sort | DFS or BFS (Kahn's) |
| Find all paths / backtracking | DFS |
| Level-order / layer processing | BFS |
| Deep state space, avoid stack overflow | BFS or iterative DFS |
| Connected components | Either |
