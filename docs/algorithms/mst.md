# Minimum Spanning Tree (MST)

A subset of edges that connects all vertices in a weighted graph without cycles and with the minimum total edge weight.

### Visual Representation (A Tree in a Graph)
The MST (represented by `=` edges) connects all nodes with the lowest possible cost.

```text
       [1]
    (1)====(2)
     | \    |
    [3] [4] [2]
     |   \  |
    (3)====(4)
       [1]
```

---

## 1. Prim's Algorithm
Finds the MST by building the tree one node at a time, always adding the cheapest edge to a new node.

### Logic
Starts at any node and uses a **PriorityQueue** to find the next nearest neighbor not already in the MST.

### Implementation (Java)
```java
public int prim(int n, List<List<int[]>> adj) {
    boolean[] inMST = new boolean[n];
    PriorityQueue<int[]> pq = new PriorityQueue<>((a, b) -> a[1] - b[1]);
    pq.add(new int[]{0, 0}); // {node, weight}

    int totalWeight = 0, count = 0;
    while (!pq.isEmpty()) {
        int[] current = pq.poll();
        int u = current[0], w = current[1];

        if (inMST[u]) continue;
        inMST[u] = true;
        totalWeight += w;
        count++;

        for (int[] edge : adj.get(u)) {
            if (!inMST[edge[0]]) {
                pq.add(edge);
            }
        }
    }
    return count == n ? totalWeight : -1;
}
```

---

## 2. Kruskal's Algorithm
Finds the MST by sorting all edges by weight and adding the cheapest edges as long as they don't form a cycle.

### Logic
Relies on **Union-Find** to efficiently detect and prevent cycles.

### Implementation (Java)
```java
public int kruskal(int n, int[][] edges) {
    Arrays.sort(edges, (a, b) -> a[2] - b[2]); // Sort by weight
    UnionFind uf = new UnionFind(n);

    int totalWeight = 0, edgesInMST = 0;
    for (int[] edge : edges) {
        if (uf.union(edge[0], edge[1])) {
            totalWeight += edge[2];
            edgesInMST++;
        }
    }
    return edgesInMST == n - 1 ? totalWeight : -1;
}
```

### Complexity
- **Prim's**: $O((V+E) \log V)$
- **Kruskal's**: $O(E \log E)$ or $O(E \log V)$ due to sorting.
