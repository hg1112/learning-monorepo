# Shortest Path Algorithms

Algorithms to find the shortest path between nodes in a weighted graph.

### Visual Representation (The Weighted Graph)
Weights represent distances between nodes.

```text
    (1) -- [4] -- (2)
     |           / |
    [1]       [2] [5]
     |       /     |
    (3) -- [1] -- (4)
```

---

## 1. Dijkstra's Algorithm
Finds the shortest path from a single source to all other nodes in a graph with **non-negative weights**.

### Logic
Always expand the node with the current smallest distance using a **PriorityQueue**.

### Implementation (Java)
```java
public int[] dijkstra(int start, List<List<int[]>> adj, int n) {
    int[] dist = new int[n];
    Arrays.fill(dist, Integer.MAX_VALUE);
    dist[start] = 0;

    PriorityQueue<int[]> pq = new PriorityQueue<>((a, b) -> a[1] - b[1]);
    pq.add(new int[]{start, 0});

    while (!pq.isEmpty()) {
        int[] current = pq.poll();
        int u = current[0], d = current[1];

        if (d > dist[u]) continue;

        for (int[] edge : adj.get(u)) {
            int v = edge[0], weight = edge[1];
            if (dist[u] + weight < dist[v]) {
                dist[v] = dist[u] + weight;
                pq.add(new int[]{v, dist[v]});
            }
        }
    }
    return dist;
}
```

**Complexity**: $O((V+E) \log V)$

---

## 2. Bellman-Ford Algorithm
Finds the shortest path from a single source in graphs with **negative weights** and detects **negative cycles**.

### Logic
Relaxes all edges $(V-1)$ times. A $V$-th relaxation indicates a negative cycle.

### Implementation (Java)
```java
public int[] bellmanFord(int start, int[][] edges, int n) {
    int[] dist = new int[n];
    Arrays.fill(dist, Integer.MAX_VALUE);
    dist[start] = 0;

    for (int i = 0; i < n - 1; i++) {
        for (int[] edge : edges) {
            int u = edge[0], v = edge[1], w = edge[2];
            if (dist[u] != Integer.MAX_VALUE && dist[u] + w < dist[v]) {
                dist[v] = dist[u] + w;
            }
        }
    }

    // Check for negative cycles
    for (int[] edge : edges) {
        int u = edge[0], v = edge[1], w = edge[2];
        if (dist[u] != Integer.MAX_VALUE && dist[u] + w < dist[v]) {
            System.out.println("Negative cycle detected!");
            return null;
        }
    }
    return dist;
}
```

**Complexity**: $O(V \cdot E)$
