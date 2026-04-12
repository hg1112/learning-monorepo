# Topological Sort

A linear ordering of vertices in a Directed Acyclic Graph (DAG) such that for every directed edge `uv`, vertex `u` comes before `v` in the ordering.

### Visual Representation (Task Dependencies)
You must finish task `A` before task `B`.

```text
( A ) ----> ( B ) ----> ( D )
  \          ^           ^
   \--[ C ]--/           |
                         /
      ( E )-------------/
```

**Order:** A -> C -> B -> E -> D

---

## 1. Kahn's Algorithm (BFS-based)
Relies on tracking the **in-degree** of each vertex.

### Logic
Always pick nodes with in-degree 0 and add them to the queue. When a node is processed, decrease the in-degree of its neighbors.

### Implementation (Java)
```java
public int[] kahn(int n, List<List<Integer>> adj) {
    int[] inDegree = new int[n];
    for (int i = 0; i < n; i++) {
        for (int neighbor : adj.get(i)) inDegree[neighbor]++;
    }

    Queue<Integer> q = new LinkedList<>();
    for (int i = 0; i < n; i++) {
        if (inDegree[i] == 0) q.add(i);
    }

    int[] result = new int[n];
    int count = 0;
    while (!q.isEmpty()) {
        int u = q.poll();
        result[count++] = u;

        for (int v : adj.get(u)) {
            if (--inDegree[v] == 0) q.add(v);
        }
    }
    return count == n ? result : new int[0]; // Empty if cycle exists
}
```

---

## 2. DFS-based Topological Sort
Relies on finishing all children before adding the parent to the result.

### Logic
Perform DFS on all unvisited nodes. Once a node has no more neighbors to visit, push it to a stack. The stack will contain the reverse ordering.

### Implementation (Java)
```java
public int[] topoSortDFS(int n, List<List<Integer>> adj) {
    boolean[] visited = new boolean[n];
    Stack<Integer> stack = new Stack<>();

    for (int i = 0; i < n; i++) {
        if (!visited[i]) dfs(i, adj, visited, stack);
    }

    int[] result = new int[n];
    int i = 0;
    while (!stack.isEmpty()) result[i++] = stack.pop();
    return result;
}

private void dfs(int u, List<List<Integer>> adj, boolean[] visited, Stack<Integer> stack) {
    visited[u] = true;
    for (int v : adj.get(u)) {
        if (!visited[v]) dfs(v, adj, visited, stack);
    }
    stack.push(u); // Finish time
}
```

### Complexity
- **Time**: $O(V + E)$
- **Space**: $O(V)$
