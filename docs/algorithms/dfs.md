# Depth-First Search (DFS)

An algorithm for traversing or searching tree or graph data structures by exploring as far as possible along each branch before backtracking.

### Visual Representation (Deep First)
DFS uses a stack (either the call stack or an explicit `Stack` object).

```text
Level 0:          ( A )
                   / \
Level 1:        ( B ) ( E )
                 / \     \
Level 2:      ( C )( D ) ( F )

Traveral Order: A -> B -> C -> D -> E -> F
```

### Recursive Implementation (The Standard)
The most common way to implement DFS, using the system call stack.

```java
public void dfsRecursive(int node, List<List<Integer>> adj, boolean[] visited) {
    visited[node] = true;
    System.out.println(node);

    for (int neighbor : adj.get(node)) {
        if (!visited[neighbor]) {
            dfsRecursive(neighbor, adj, visited);
        }
    }
}
```

### Iterative Implementation
Uses an explicit `Stack` data structure.

```java
public void dfsIterative(int start, List<List<Integer>> adj, int n) {
    boolean[] visited = new boolean[n];
    Stack<Integer> stack = new Stack<>();

    stack.push(start);

    while (!stack.isEmpty()) {
        int node = stack.pop();

        if (!visited[node]) {
            visited[node] = true;
            System.out.println(node);

            // Add neighbors to stack
            for (int neighbor : adj.get(node)) {
                if (!visited[neighbor]) {
                    stack.push(neighbor);
                }
            }
        }
    }
}
```

### Complexity
- **Time**: $O(V + E)$
- **Space**: $O(V)$ for the recursion stack or explicit stack.
