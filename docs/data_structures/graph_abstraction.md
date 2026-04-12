# Graph Abstraction

A reusable generic Graph implementation lives at:
[shared/graphs/Graph.java](../../shared/graphs/Graph.java)

---

## Adjacency List Representation

The graph is stored as `Map<V, List<Edge<V>>>` where `V` is the vertex type.
Memory-efficient for sparse graphs (E << V²).

```text
Vertices: A, B, C, D

Adjacency List:
( A ) -> [ (B,4), (C,1) ]
( B ) -> [ (D,2) ]
( C ) -> [ (B,2), (D,5) ]
( D ) -> [ ]

Graph:
    (A) --[4]--> (B)
     |          /
    [1]       [2]
     |       /
     v      v
    (C) --[5]--> (D)
```

---

## What's Implemented

| Algorithm | Method | Returns |
|-----------|--------|---------|
| BFS | `bfs(start)` | Visited order |
| DFS | `dfs(start)` | Visited order |
| Dijkstra | `dijkstra(start)` | `Map<V, Integer>` distances |
| Bellman-Ford | `bellmanFord(start)` | `Map<V, Integer>` distances (handles negative weights) |
| Prim's MST | `primMST()` | List of edges |
| Kruskal's MST | `kruskalMST()` | List of edges |
| Topological sort | `topologicalSort()` | Ordered list (Kahn's BFS-based) |

**Helper classes included:** `Edge<V>`, `NodeDistance<V>`, `FullEdge<V>`, `UnionFind<V>`

---

## Core Components

### Edge Structure

```java
public static class Edge<V> {
    public final V to;
    public final int weight;
    // unweighted edge: weight defaults to 1
}
```

### Directed vs Undirected

```java
Graph<String> directed   = new Graph<>(true);   // addEdge adds A→B only
Graph<String> undirected = new Graph<>(false);  // addEdge adds both A→B and B→A
```

---

## Graph Representations Compared

| Representation | Space | addEdge | hasEdge | Neighbors |
|---------------|-------|---------|---------|-----------|
| Adjacency List | O(V+E) | O(1) | O(degree) | O(degree) |
| Adjacency Matrix | O(V²) | O(1) | O(1) | O(V) |
| Edge List | O(E) | O(1) | O(E) | O(E) |

Use **adjacency matrix** only for dense graphs (E ≈ V²) or when O(1) `hasEdge` is critical.
Use **edge list** when the only operation needed is iterating all edges (e.g., Kruskal's MST).

---

## Usage Example

```java
Graph<String> g = new Graph<>(true);  // directed
g.addEdge("NYC", "London", 3400);
g.addEdge("London", "Paris", 214);
g.addEdge("NYC", "Paris", 3600);

Map<String, Integer> dist = g.dijkstra("NYC");
// dist.get("Paris") == 3414  (NYC→London→Paris is shorter)
```

---

## Complexity

| Operation | Time | Space |
|-----------|------|-------|
| addVertex | O(1) | — |
| addEdge | O(1) | — |
| BFS / DFS | O(V+E) | O(V) |
| Dijkstra | O((V+E) log V) | O(V) |
| Bellman-Ford | O(V×E) | O(V) |
| Prim's MST | O((V+E) log V) | O(V) |
| Kruskal's MST | O(E log E) | O(V) for Union-Find |
| Topological sort | O(V+E) | O(V) |
| Total space | — | O(V+E) |

---

## Quick Algorithm Cross-Reference

| Need | See |
|------|-----|
| Shortest path in unweighted graph | `docs/algorithms/bfs.md` |
| Shortest path in weighted graph (no neg) | `docs/algorithms/shortest_path.md` |
| Shortest path with negative edges | `docs/algorithms/shortest_path.md` (Bellman-Ford) |
| Minimum spanning tree | `docs/algorithms/mst.md` |
| Dependency ordering | `docs/algorithms/topological_sort.md` |
| Connected components | `docs/data_structures/union_find.md` |
