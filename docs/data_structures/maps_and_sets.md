# Maps & Sets

Data structures that store unique elements (Sets) or key-value pairs (Maps).

---

## 1. HashMap & HashSet (Unordered)
Uses a **Hash Table** for constant-time access. Order of elements is NOT preserved.

### Mechanics
1.  **Hash Function**: Converts a key into a numeric index.
2.  **Buckets**: Elements are stored in buckets. Collisions are handled via linked lists or balanced trees (in Java 8+).

### Java API
```java
Map<String, Integer> map = new HashMap<>();
map.put("A", 1);        // O(1)
map.get("A");           // O(1)
map.containsKey("B");   // O(1)
map.getOrDefault("C", 0); // Safe access

Set<String> set = new HashSet<>();
set.add("A");           // O(1)
set.contains("A");      // O(1)
```

---

## 2. LinkedHashMap & LinkedHashSet (Insertion Order)
A variant of HashMap/HashSet that maintains a **doubly-linked list** across all entries.

### Visual Representation
```text
Bucket Index:    0      1      2      3
HashMap Array: [Null][Entry1][Null][Entry2]
                         ^            |
Insertion Order: [Head] -> [Entry1] -> [Entry2] -> [Tail]
```

### Key Property
Preserves the order in which items were inserted.

---

## 3. TreeMap & TreeSet (Sorted Order)
Maintains elements in **natural sorted order** (or via a custom comparator) using a **Red-Black Tree**.

### Mechanics
- `firstKey()` / `lastKey()`: $O(\log N)$.
- `ceilingKey(X)` / `floorKey(X)`: $O(\log N)$.
- `subMap(low, high)`: $O(\log N)$ to find, then iteration.

### Java API
```java
TreeMap<Integer, String> treeMap = new TreeMap<>();
treeMap.put(10, "A");
treeMap.put(5, "B");
treeMap.put(20, "C");

treeMap.firstKey();     // 5
treeMap.lastKey();      // 20
treeMap.ceilingKey(12); // 20 (Smallest key >= 12)
```

---

## Summary Table

| Feature | HashMap/Set | LinkedHashMap/Set | TreeMap/Set |
| :--- | :--- | :--- | :--- |
| **Search/Insert** | $O(1)$ | $O(1)$ | $O(\log N)$ |
| **Iteration Order** | Random | Insertion Order | Sorted Order |
| **Null Keys** | Allowed (one) | Allowed (one) | Not Allowed |
| **Memory** | Lower | Moderate | Higher |

---

## Use Case Tips
-   **Default**: Use `HashMap` or `HashSet`.
-   **When Order Matters**: Use `LinkedHashMap` or `LinkedHashSet`.
-   **When Range Queries Matter**: Use `TreeMap` or `TreeSet` (finding the smallest value $> X$, etc.).
