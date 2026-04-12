# Java TreeMap — Implementation & Time Complexity

## Underlying Data Structure

TreeMap is implemented as a **Red-Black Tree** — a self-balancing Binary Search Tree (BST).

### Red-Black Tree Properties

1. Every node is either **Red** or **Black**
2. Root is always **Black**
3. No two consecutive Red nodes (Red node's parent/children must be Black)
4. Every path from root to a null leaf has the **same number of Black nodes**
5. Null leaves are considered Black

These constraints guarantee the tree height is always **O(log n)**, where `n` = number of entries.

---

## Internal Node Structure

```java
// Simplified from JDK source (java.util.TreeMap)
static final class Entry<K,V> {
    K key;
    V value;
    Entry<K,V> left;
    Entry<K,V> right;
    Entry<K,V> parent;
    boolean color = BLACK;  // true = BLACK, false = RED
}
```

---

## Operation Time Complexities

### Core Map Operations

| Operation | Time | Explanation |
|-----------|------|-------------|
| `get(key)` | **O(log n)** | BST traversal from root — compare at each node, go left/right |
| `put(key, val)` | **O(log n)** | BST insert + at most O(log n) rebalancing rotations |
| `remove(key)` | **O(log n)** | BST delete + rebalancing |
| `containsKey(key)` | **O(log n)** | Same as `get` |
| `containsValue(val)` | **O(n)** | No index on values — must scan every node |
| `size()` | **O(1)** | Maintained as a field |

### Navigation Operations (TreeMap-specific)

All navigation operations walk the tree — bounded by height = **O(log n)**.

| Operation | Time | What it does |
|-----------|------|-------------|
| `firstKey()` | **O(log n)** | Walk left until leftmost node |
| `lastKey()` | **O(log n)** | Walk right until rightmost node |
| `floorKey(k)` | **O(log n)** | Largest key ≤ k |
| `ceilingKey(k)` | **O(log n)** | Smallest key ≥ k |
| `lowerKey(k)` | **O(log n)** | Largest key **strictly <** k |
| `higherKey(k)` | **O(log n)** | Smallest key **strictly >** k |
| `pollFirstEntry()` | **O(log n)** | Remove + return min entry |
| `pollLastEntry()` | **O(log n)** | Remove + return max entry |

### SubMap / Range Views

```java
// All return a *view* backed by the same tree — O(1) to create
map.subMap(fromKey, toKey)                        // [fromKey, toKey)
map.subMap(from, inclusive, to, inclusive)
map.headMap(toKey)                                // [first, toKey)
map.tailMap(fromKey)                              // [fromKey, last]
```

| Operation | Time | Note |
|-----------|------|------|
| `subMap(from, to)` | **O(1)** | Returns a view, no copying |
| `headMap(to)` | **O(1)** | View |
| `tailMap(from)` | **O(1)** | View |
| Operations *on* the subMap | **O(log n)** | Still backed by the full tree |
| Iterating the subMap | **O(k + log n)** | k = elements in range, log n to find start |

### Iteration

```java
for (Map.Entry<K,V> e : map.entrySet()) { ... }
```

| Operation | Time | Note |
|-----------|------|------|
| Full iteration | **O(n)** | In-order traversal of the BST |
| `entrySet()` / `keySet()` / `values()` | **O(1)** | Returns a view |

The iterator uses a **successor pointer** trick — it finds `firstKey()` in O(log n), then each
`next()` call finds the in-order successor in **amortized O(1)** (total traversal cost is O(n)).

---

## Visual Example

```
         8(B)
        /    \
      4(R)   12(R)
      / \    /  \
    2(B) 6(B) 10(B) 14(B)
```

`floorKey(11)` → starts at 8, goes right to 12 (too big, record 8), goes left to 10 (≤ 11,
record 10), no right child → **returns 10**. Took 3 comparisons = O(log n).

---

## Practical Code

```java
TreeMap<Integer, String> map = new TreeMap<>();
map.put(5, "five");
map.put(2, "two");
map.put(8, "eight");
map.put(1, "one");
map.put(4, "four");

map.firstKey();          // 1  — O(log n)
map.lastKey();           // 8  — O(log n)
map.floorKey(3);         // 2  — largest key <= 3
map.ceilingKey(3);       // 4  — smallest key >= 3
map.lowerKey(5);         // 4  — strictly < 5
map.higherKey(5);        // 8  — strictly > 5

// Range view — O(1) to create, O(k + log n) to iterate
map.subMap(2, 6);        // {2=two, 4=four, 5=five}
map.headMap(5);          // {1=one, 2=two, 4=four}
map.tailMap(5);          // {5=five, 8=eight}
```

---

## TreeMap vs HashMap — When to Use Each

| Need | Use |
|------|-----|
| O(1) average get/put, no ordering needed | `HashMap` |
| Sorted keys, range queries, min/max | `TreeMap` |
| LRU cache, insertion-order iteration | `LinkedHashMap` |
| Concurrent sorted map | `ConcurrentSkipListMap` |

---

## Summary

| Operation Category | Time |
|-------------------|------|
| Basic get/put/remove | O(log n) |
| Min/Max (first/last) | O(log n) |
| Floor/Ceiling/Lower/Higher | O(log n) |
| SubMap creation (view) | O(1) |
| SubMap iteration (k elements) | O(k + log n) |
| Full iteration | O(n) |
| containsValue | O(n) |
| size | O(1) |
