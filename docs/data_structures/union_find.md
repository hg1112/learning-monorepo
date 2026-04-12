# Union-Find (Disjoint Set Union)

A data structure that tracks elements partitioned into disjoint (non-overlapping) sets.

### Visual Representation (Union & Find)
Elements start as their own parents. We merge them by pointing their roots to each other.

```text
Initial Sets:  {0}  {1}  {2}  {3}  {4}

1. union(0, 1)  {0, 1} {2} {3} {4}
    0 -> 1 (0 points to 1)

2. union(2, 3)  {0, 1} {2, 3} {4}
    2 -> 3 (2 points to 3)

3. union(1, 3)  {0, 1, 2, 3} {4}
    1 -> 3 (Sets merged via roots)
```

### Optimizations
- **Union by Rank**: Always point the root of the "shorter" tree to the "taller" one.
- **Path Compression**: After finding a root, point all nodes in the path directly to it.

```text
Before Path Compression:       After find(5):
      [ 1 ]                          [ 1 ]
       /                            / | \
     [ 2 ]                        [ 2][3][5]
     /
   [ 3 ]
   /
 [ 5 ]
```

### Complexity
- **Time**: Amortized $O(\alpha(N))$ per operation (where $\alpha$ is the Inverse Ackermann Function, nearly $O(1)$).
- **Space**: $O(N)$ for `parent` and `rank` arrays.
