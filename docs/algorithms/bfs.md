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

### Iterative Implementation (The Standard)
The standard approach using a `Queue` to track the current layer of nodes.

```java
public void bfsIterative(Node start) {
    Queue<Node> queue = new LinkedList<>();
    Set<Node> visited = new HashSet<>();

    queue.add(start);
    visited.add(start);

    while (!queue.isEmpty()) {
        Node current = queue.poll();
        System.out.println(current.val);
        for (Node neighbor : current.neighbors) {
            if (!visited.contains(neighbor)) {
                queue.add(neighbor);
                visited.add(neighbor);
            }
        }
    }
}
```

### Recursive Implementation (Level Order for Trees)
For trees, we can use recursion by passing the "level" to a helper function. Note: For graphs, this is rarely used.

```java
public void bfsRecursive(TreeNode root) {
    int height = getHeight(root);
    for (int i = 1; i <= height; i++) {
        printCurrentLevel(root, i);
    }
}

private void printCurrentLevel(TreeNode node, int level) {
    if (node == null) return;
    if (level == 1) {
        System.out.println(node.val);
    } else {
        printCurrentLevel(node.left, level - 1);
        printCurrentLevel(node.right, level - 1);
    }
}
```

### Key Use Cases
- Shortest path in an unweighted graph
- Level order traversal
- Finding all reachable nodes

### Complexity
- **Time**: $O(V + E)$ where $V$ is vertices and $E$ is edges.
- **Space**: $O(V)$ for the queue and visited set.
