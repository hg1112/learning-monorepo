# Breadth-First Search (BFS)

An algorithm for traversing graphs or trees layer by layer, visiting all nodes at
distance d before any node at distance d+1. Guarantees shortest path in unweighted graphs.

---

## Core BFS

### Visual — Layer by Layer

```text
Level 0:          ( A )
                   / \
Level 1:        ( B ) ( C )
                 / \     \
Level 2:      ( D )( E ) ( F )

Queue progression:
[A] → [B, C] → [C, D, E] → [D, E, F] → [E, F] → [F] → []
Visit order: A B C D E F
```

### Standard Iterative Template

```java
public void bfs(int start, List<List<Integer>> adj, int n) {
    Queue<Integer> queue = new LinkedList<>();
    boolean[] visited = new boolean[n];

    queue.offer(start);
    visited[start] = true;

    while (!queue.isEmpty()) {
        int node = queue.poll();
        // process node here

        for (int neighbor : adj.get(node)) {
            if (!visited[neighbor]) {
                visited[neighbor] = true;  // mark on enqueue, not dequeue
                queue.offer(neighbor);
            }
        }
    }
}
```

**Mark visited on enqueue** (not on dequeue) — otherwise the same node can be enqueued
multiple times before it's processed, wasting O(E) extra work.

### Shortest Path in Unweighted Graph

```java
public int[] bfsDistances(int start, List<List<Integer>> adj, int n) {
    int[] dist = new int[n];
    Arrays.fill(dist, -1);
    Queue<Integer> q = new LinkedList<>();
    dist[start] = 0;
    q.offer(start);

    while (!q.isEmpty()) {
        int u = q.poll();
        for (int v : adj.get(u)) {
            if (dist[v] == -1) {
                dist[v] = dist[u] + 1;
                q.offer(v);
            }
        }
    }
    return dist;
}
```

### Complexity

| | Time | Space |
|-|------|-------|
| BFS | O(V + E) | O(V) |
| Shortest path (unweighted) | O(V + E) | O(V) |

---

## Multi-Source BFS

Start BFS simultaneously from multiple sources by seeding all of them at distance 0.

```java
// Example: "Walls and Gates" — distance from each empty room to nearest gate
Queue<int[]> q = new LinkedList<>();
for (int r = 0; r < m; r++)
    for (int c = 0; c < n; c++)
        if (grid[r][c] == 0)  // gate
            q.offer(new int[]{r, c});

int[] dr = {0,0,1,-1}, dc = {1,-1,0,0};
while (!q.isEmpty()) {
    int[] cell = q.poll();
    for (int d = 0; d < 4; d++) {
        int nr = cell[0]+dr[d], nc = cell[1]+dc[d];
        if (nr>=0 && nr<m && nc>=0 && nc<n && grid[nr][nc] == Integer.MAX_VALUE) {
            grid[nr][nc] = grid[cell[0]][cell[1]] + 1;
            q.offer(new int[]{nr, nc});
        }
    }
}
```

---

## Bidirectional BFS

Run two BFS frontiers simultaneously — one from `start`, one from `end`.
Stop when they meet. Combined search area ≈ **half** of unidirectional BFS:

```
Unidirectional: one sphere of radius d     → O(b^d)
Bidirectional:  two spheres of radius d/2  → O(2 × b^(d/2))  where b = branching factor
```

```java
public int bidirectionalBFS(int start, int end, List<List<Integer>> adj) {
    if (start == end) return 0;
    Set<Integer> frontS = new HashSet<>(), frontE = new HashSet<>();
    Set<Integer> visitedS = new HashSet<>(), visitedE = new HashSet<>();
    frontS.add(start); visitedS.add(start);
    frontE.add(end);   visitedE.add(end);
    int steps = 0;

    while (!frontS.isEmpty() && !frontE.isEmpty()) {
        steps++;
        // always expand the smaller frontier
        if (frontS.size() > frontE.size()) {
            Set<Integer> tmp = frontS; frontS = frontE; frontE = tmp;
            tmp = visitedS; visitedS = visitedE; visitedE = tmp;
        }
        Set<Integer> next = new HashSet<>();
        for (int node : frontS) {
            for (int nb : adj.get(node)) {
                if (visitedE.contains(nb)) return steps;   // frontiers met
                if (!visitedS.contains(nb)) {
                    next.add(nb);
                    visitedS.add(nb);
                }
            }
        }
        frontS = next;
    }
    return -1;   // unreachable
}
```

**Always expand the smaller frontier** — keeps both frontiers balanced, maximizing savings.

---

## Worked Example — Minimum Knight Moves

**LeetCode 1197** | [Solution.java](../../challenges/leetcode/MinimumKnightMoves/Solution.java)

**Problem:** Knight at (0,0) on an infinite board. Find minimum moves to reach (x, y).

### Symmetry — fold to first octant

`f(x, y) = f(|x|, |y|)` — the knight's move set is fully symmetric.
Swap if needed so `x ≥ y ≥ 0` (first octant).

### Board Pattern

```
  0  1  2  3  4  5  6  7
0 0  3  2  3  2  3  4  5
1    2  1  2  3  2  3  4
2       4  3  2  3  4  5   ← (2,2)=4 is the only diagonal anomaly
3          2  3  4  3  4
4             4  3  4  3
```

### Critical Insight: Paths Can Go Negative

`f(1,1) = 2` via `(0,0)→(2,−1)→(1,1)`. BFS that disallows negative coordinates
returns 4 instead. Allow coordinates down to **−1** — no optimal path ever needs −2.

### Approach 1 — BFS (Good)

```java
public int minKnightMoves(int x, int y) {
    x = Math.abs(x); y = Math.abs(y);
    int[][] dirs = {{1,2},{2,1},{2,-1},{1,-2},{-1,-2},{-2,-1},{-2,1},{-1,2}};
    Map<String, Integer> dist = new HashMap<>();
    Queue<int[]> q = new LinkedList<>();
    q.offer(new int[]{0,0}); dist.put("0_0", 0);

    while (!q.isEmpty()) {
        int[] cur = q.poll();
        int d = dist.get(cur[0]+"_"+cur[1]);
        if (cur[0]==x && cur[1]==y) return d;
        for (int[] dir : dirs) {
            int nx = cur[0]+dir[0], ny = cur[1]+dir[1];
            String key = nx+"_"+ny;
            if (!dist.containsKey(key) && nx >= -1 && ny >= -1) {
                dist.put(key, d+1);
                q.offer(new int[]{nx, ny});
            }
        }
    }
    return -1;
}
// Time: O(N²), Space: O(N²)  where N = max(|x|,|y|)
```

**Why −1 bound?** The path to any first-quadrant target passes through at most
one negative coordinate of value −1.

### Approach 2 — DFS + Memoization (Better)

```
f(x, y) = 1 + min( f(|x−1|, |y−2|), f(|x−2|, |y−1|) )

Base cases:
  f(0, 0) = 0
  f(x, y) = 2   when x + y == 2        ← covers (2,0),(0,2),(1,1)
```

The `x+y==2` base case encodes paths that go through a negative coordinate
(otherwise `f(1,0) → f(0,2) → f(2,0) → f(1,0)` forms a cycle).

```java
Map<String, Integer> memo = new HashMap<>();
public int dfs(int x, int y) {
    if (x == 0 && y == 0) return 0;
    if (x + y == 2) return 2;
    String key = x+","+y;
    if (memo.containsKey(key)) return memo.get(key);
    int res = 1 + Math.min(dfs(Math.abs(x-1), Math.abs(y-2)),
                           dfs(Math.abs(x-2), Math.abs(y-1)));
    memo.put(key, res);
    return res;
}
// Time: O(|x|×|y|), Space: O(|x|×|y|)
```

### Approach 3 — O(1) Formula (Optimal)

After normalizing `x ≥ y ≥ 0`, let `delta = x − y`.

**Near-diagonal** (y > delta, i.e., x < 2y):
```
answer = delta − 2 × floor((delta − y) / 3)
```

**Near x-axis** (y ≤ delta, i.e., x ≥ 2y):
```
answer = delta − 2 × floor((delta − y) / 4)
```

**Special cases** (formula fails here):
| Cell | Formula gives | Correct |
|------|--------------|---------|
| (1, 0) | 1 | **3** |
| (2, 2) | 2 | **4** |

**Java note:** The near-diagonal branch requires `Math.floor` (float division), because
`delta−y < 0` and Java integer division truncates toward zero, not floor:
```java
// Wrong: (-1) / 3 == 0 in Java integer division
// Correct: Math.floor(-1.0 / 3) == -1.0
```

### Solution Comparison

| Approach | Correct | Time | Space |
|----------|---------|------|-------|
| Buggy bidirectional BFS | No (4 bugs) | — | — |
| BFS (allow −1) | Yes | O(N²) | O(N²) |
| DFS + memoization | Yes | O(\|x\|×\|y\|) | O(\|x\|×\|y\|) |
| O(1) formula | Yes | **O(1)** | **O(1)** |

**4 Bugs in the bidirectional BFS:**
1. `HashMap<Integer,Integer>` — same x overwrites different y entries
2. Target frontier uses taxi-cab directions instead of knight moves
3. Source and target frontier maps are wired backwards
4. `ans` defaults to 0 — returns 0 on failure

---

## Complexity

| Variant | Time | Space | Notes |
|---------|------|-------|-------|
| Standard BFS | O(V+E) | O(V) | |
| Multi-source BFS | O(V+E) | O(V) | All sources pre-seeded at distance 0 |
| Bidirectional BFS | O(b^(d/2)) | O(b^(d/2)) | b=branching, d=distance |
| BFS on grid (m×n) | O(m×n) | O(m×n) | |

---

## When to Use BFS

- Shortest path in **unweighted** graph (use Dijkstra for weighted)
- Level-order traversal of a tree
- Finding all nodes at distance exactly k
- Multi-source distance (walls and gates, rotting oranges)
- Detecting bipartiteness (2-coloring during BFS)
- When DFS would cause stack overflow (very deep graphs)
