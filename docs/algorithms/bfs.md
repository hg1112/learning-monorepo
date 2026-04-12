# Breadth-First Search (BFS)

An algorithm for traversing or searching tree or graph data structures layer by layer.

### Visual Representation (Layer by Layer)
BFS explores all neighbors at distance $D$ before moving to $D+1$.

```text
Level 0:          ( A )
                   / \
Level 1:        ( B ) ( C )
                 / \     \
Level 2:      ( D )( E ) ( F )

1. Queue: [ A ]
2. Queue: [ B, C ]
3. Queue: [ C, D, E ]
4. Queue: [ D, E, F ]
```

### Key Use Cases
- Shortest path in an unweighted graph
- Level order traversal
- Finding all reachable nodes

### Template (Java)
```java
Queue<Node> queue = new LinkedList<>();
queue.add(start);
visited.add(start);

while (!queue.isEmpty()) {
    Node current = queue.poll();
    for (Node neighbor : current.neighbors) {
        if (!visited.contains(neighbor)) {
            queue.add(neighbor);
            visited.add(neighbor);
        }
    }
}
```

### Complexity
- **Time**: $O(V + E)$ where $V$ is vertices and $E$ is edges.
- **Space**: $O(V)$ for the queue and visited set.
