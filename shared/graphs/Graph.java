package shared.graphs;

import java.util.*;

/**
 * A clean, generic Graph abstraction using an Adjacency List.
 * Supports weighted/unweighted, directed/undirected graphs.
 * 
 * Includes implementations for:
 * - BFS, DFS
 * - Dijkstra, Bellman-Ford
 * - Prim's, Kruskal's
 * - Topological Sort (Kahn's and DFS-based)
 */
public class Graph<V> {
    private final Map<V, List<Edge<V>>> adjList = new HashMap<>();
    private final boolean isDirected;

    public Graph(boolean isDirected) {
        this.isDirected = isDirected;
    }

    public void addVertex(V v) {
        adjList.putIfAbsent(v, new ArrayList<>());
    }

    public void addEdge(V u, V v, int weight) {
        addVertex(u);
        addVertex(v);
        adjList.get(u).add(new Edge<>(v, weight));
        if (!isDirected) {
            adjList.get(v).add(new Edge<>(u, weight));
        }
    }

    public void addEdge(V u, V v) {
        addEdge(u, v, 0); // Default weight 0 for unweighted graphs
    }

    public List<Edge<V>> getNeighbors(V v) {
        return adjList.getOrDefault(v, Collections.emptyList());
    }

    public Set<V> getVertices() {
        return adjList.keySet();
    }

    // --- TRAVERSALS ---

    public List<V> bfs(V start) {
        List<V> result = new ArrayList<>();
        Set<V> visited = new HashSet<>();
        Queue<V> queue = new LinkedList<>();

        queue.add(start);
        visited.add(start);

        while (!queue.isEmpty()) {
            V u = queue.poll();
            result.add(u);
            for (Edge<V> edge : getNeighbors(u)) {
                if (!visited.contains(edge.to)) {
                    visited.add(edge.to);
                    queue.add(edge.to);
                }
            }
        }
        return result;
    }

    public List<V> dfs(V start) {
        List<V> result = new ArrayList<>();
        dfsHelper(start, new HashSet<>(), result);
        return result;
    }

    private void dfsHelper(V u, Set<V> visited, List<V> result) {
        visited.add(u);
        result.add(u);
        for (Edge<V> edge : getNeighbors(u)) {
            if (!visited.contains(edge.to)) {
                dfsHelper(edge.to, visited, result);
            }
        }
    }

    // --- SHORTEST PATH ---

    public Map<V, Integer> dijkstra(V start) {
        Map<V, Integer> dist = new HashMap<>();
        for (V v : adjList.keySet()) dist.put(v, Integer.MAX_VALUE);
        dist.put(start, 0);

        PriorityQueue<NodeDistance<V>> pq = new PriorityQueue<>(Comparator.comparingInt(n -> n.distance));
        pq.add(new NodeDistance<>(start, 0));

        while (!pq.isEmpty()) {
            NodeDistance<V> current = pq.poll();
            V u = current.node;

            if (current.distance > dist.get(u)) continue;

            for (Edge<V> edge : getNeighbors(u)) {
                int newDist = dist.get(u) + edge.weight;
                if (newDist < dist.get(edge.to)) {
                    dist.put(edge.to, newDist);
                    pq.add(new NodeDistance<>(edge.to, newDist));
                }
            }
        }
        return dist;
    }

    public Map<V, Integer> bellmanFord(V start) {
        Map<V, Integer> dist = new HashMap<>();
        for (V v : adjList.keySet()) dist.put(v, Integer.MAX_VALUE);
        dist.put(start, 0);

        int vCount = adjList.size();
        for (int i = 0; i < vCount - 1; i++) {
            for (V u : adjList.keySet()) {
                if (dist.get(u) == Integer.MAX_VALUE) continue;
                for (Edge<V> edge : getNeighbors(u)) {
                    if (dist.get(u) + edge.weight < dist.get(edge.to)) {
                        dist.put(edge.to, dist.get(u) + edge.weight);
                    }
                }
            }
        }

        // Check for negative cycles
        for (V u : adjList.keySet()) {
            if (dist.get(u) == Integer.MAX_VALUE) continue;
            for (Edge<V> edge : getNeighbors(u)) {
                if (dist.get(u) + edge.weight < dist.get(edge.to)) {
                    throw new RuntimeException("Graph contains a negative weight cycle");
                }
            }
        }

        return dist;
    }

    // --- MINIMUM SPANNING TREE ---

    public int primMST() {
        if (adjList.isEmpty()) return 0;

        V start = adjList.keySet().iterator().next();
        Set<V> visited = new HashSet<>();
        PriorityQueue<NodeDistance<V>> pq = new PriorityQueue<>(Comparator.comparingInt(n -> n.distance));
        
        pq.add(new NodeDistance<>(start, 0));
        int totalWeight = 0;

        while (!pq.isEmpty()) {
            NodeDistance<V> current = pq.poll();
            V u = current.node;

            if (visited.contains(u)) continue;
            visited.add(u);
            totalWeight += current.distance;

            for (Edge<V> edge : getNeighbors(u)) {
                if (!visited.contains(edge.to)) {
                    pq.add(new NodeDistance<>(edge.to, edge.weight));
                }
            }
        }

        return visited.size() == adjList.size() ? totalWeight : -1; // -1 if graph is not connected
    }

    public int kruskalMST() {
        List<FullEdge<V>> allEdges = new ArrayList<>();
        for (V u : adjList.keySet()) {
            for (Edge<V> edge : adjList.get(u)) {
                allEdges.add(new FullEdge<>(u, edge.to, edge.weight));
            }
        }
        Collections.sort(allEdges, Comparator.comparingInt(e -> e.weight));

        UnionFind<V> uf = new UnionFind<>(adjList.keySet());
        int totalWeight = 0;
        int edgeCount = 0;

        for (FullEdge<V> edge : allEdges) {
            if (uf.union(edge.from, edge.to)) {
                totalWeight += edge.weight;
                edgeCount++;
            }
        }

        return edgeCount == adjList.size() - 1 ? totalWeight : -1;
    }

    // --- TOPOLOGICAL SORT (For Directed Acyclic Graphs) ---

    public List<V> topologicalSort() {
        Map<V, Integer> inDegree = new HashMap<>();
        for (V u : adjList.keySet()) {
            inDegree.putIfAbsent(u, 0);
            for (Edge<V> edge : adjList.get(u)) {
                inDegree.put(edge.to, inDegree.getOrDefault(edge.to, 0) + 1);
            }
        }

        Queue<V> queue = new LinkedList<>();
        for (V v : inDegree.keySet()) {
            if (inDegree.get(v) == 0) queue.add(v);
        }

        List<V> result = new ArrayList<>();
        while (!queue.isEmpty()) {
            V u = queue.poll();
            result.add(u);
            for (Edge<V> edge : getNeighbors(u)) {
                inDegree.put(edge.to, inDegree.get(edge.to) - 1);
                if (inDegree.get(edge.to) == 0) queue.add(edge.to);
            }
        }

        return result.size() == adjList.size() ? result : Collections.emptyList(); // Cycle check
    }

    // --- HELPER CLASSES ---

    public static class Edge<V> {
        public final V to;
        public final int weight;

        public Edge(V to, int weight) {
            this.to = to;
            this.weight = weight;
        }
    }

    private static class NodeDistance<V> {
        public final V node;
        public final int distance;

        public NodeDistance(V node, int distance) {
            this.node = node;
            this.distance = distance;
        }
    }

    private static class FullEdge<V> {
        public final V from;
        public final V to;
        public final int weight;

        public FullEdge(V from, V to, int weight) {
            this.from = from;
            this.to = to;
            this.weight = weight;
        }
    }

    private static class UnionFind<V> {
        private final Map<V, V> parent = new HashMap<>();

        public UnionFind(Set<V> vertices) {
            for (V v : vertices) parent.put(v, v);
        }

        public V find(V i) {
            if (parent.get(i).equals(i)) return i;
            parent.put(i, find(parent.get(i))); // Path compression
            return parent.get(i);
        }

        public boolean union(V i, V j) {
            V rootI = find(i);
            V rootJ = find(j);
            if (rootI.equals(rootJ)) return false;
            parent.put(rootI, rootJ);
            return true;
        }
    }
}
