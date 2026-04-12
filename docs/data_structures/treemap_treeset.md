# TreeMap & TreeSet — Sorted Maps and Sets

Both are backed by a **Red-Black Tree** — a self-balancing BST that guarantees O(log N) for
all operations. Use when you need **sorted order**, **range queries**, or **floor/ceiling lookups**.

---

## Red-Black Tree — Internals

### Properties

1. Every node is either **Red** or **Black**
2. Root is always **Black**
3. No two consecutive Red nodes (a Red node's parent and children must be Black)
4. Every path from root to a null leaf has the **same number of Black nodes**
5. Null leaves are considered Black

These constraints guarantee tree height ≤ 2 log₂(N+1), so all operations are O(log N).

### Internal Node Structure (from JDK source)

```java
static final class Entry<K,V> {
    K key;
    V value;
    Entry<K,V> left;
    Entry<K,V> right;
    Entry<K,V> parent;
    boolean color = BLACK;   // true = BLACK, false = RED
}
```

### Visual Example

```
         8(B)
        /    \
      4(R)   12(R)
      / \    /  \
    2(B) 6(B) 10(B) 14(B)
```

`floorKey(11)` → 8 (too small, record 8) → right to 12 (too big, record 8) → left to 10 (≤11, record 10)
→ no right child → **returns 10**. Three comparisons = O(log N).

---

## TreeMap — Operation Reference

### Core Map Operations

| Operation | Time | Explanation |
|-----------|------|-------------|
| `get(key)` | O(log N) | BST traversal from root |
| `put(key, val)` | O(log N) | BST insert + at most O(log N) rebalancing |
| `remove(key)` | O(log N) | BST delete + rebalancing |
| `containsKey(key)` | O(log N) | Same as `get` |
| `containsValue(val)` | O(N) | No index on values — full scan |
| `size()` | O(1) | Maintained as a field |

### Navigation Operations (TreeMap-specific)

| Operation | Time | What it returns |
|-----------|------|----------------|
| `firstKey()` | O(log N) | Minimum key (leftmost node) |
| `lastKey()` | O(log N) | Maximum key (rightmost node) |
| `floorKey(k)` | O(log N) | Largest key ≤ k |
| `ceilingKey(k)` | O(log N) | Smallest key ≥ k |
| `lowerKey(k)` | O(log N) | Largest key **strictly <** k |
| `higherKey(k)` | O(log N) | Smallest key **strictly >** k |
| `pollFirstEntry()` | O(log N) | Remove + return min entry |
| `pollLastEntry()` | O(log N) | Remove + return max entry |
| `firstEntry()` / `lastEntry()` | O(log N) | Min/Max `Map.Entry` without removal |

### Range Views (SubMap, HeadMap, TailMap)

```java
// All return a *view* backed by the same tree — O(1) to create, O(log N) per op on the view
map.subMap(fromKey, toKey)                         // [fromKey, toKey)
map.subMap(from, fromInclusive, to, toInclusive)   // full control
map.headMap(toKey)                                 // [first, toKey)
map.headMap(toKey, inclusive)
map.tailMap(fromKey)                               // [fromKey, last]
map.tailMap(fromKey, inclusive)
```

| Operation | Time | Note |
|-----------|------|------|
| `subMap(from, to)` | O(1) | Returns a view, no copying |
| Operations on the view | O(log N) | Still backed by full tree |
| Iterating k elements of subMap | O(k + log N) | log N to find start, then in-order |

### Iteration

```java
for (Map.Entry<K,V> e : map.entrySet()) { ... }          // ascending
for (Map.Entry<K,V> e : map.descendingMap().entrySet()) { ... }  // descending
```

| Operation | Time | Note |
|-----------|------|------|
| Full iteration | O(N) | In-order traversal |
| `entrySet()` / `keySet()` / `values()` | O(1) | Returns a view |
| Each `next()` call | O(1) amortized | Successor pointer trick |

The iterator finds `firstKey()` in O(log N), then each `next()` finds the in-order
successor in amortized O(1) — total traversal is O(N).

---

## TreeMap — Practical Code

```java
TreeMap<Integer, String> map = new TreeMap<>();
map.put(5, "five");
map.put(2, "two");
map.put(8, "eight");
map.put(1, "one");
map.put(4, "four");

map.firstKey();          // 1  — O(log N)
map.lastKey();           // 8  — O(log N)
map.floorKey(3);         // 2  — largest key <= 3
map.ceilingKey(3);       // 4  — smallest key >= 3
map.lowerKey(5);         // 4  — strictly < 5
map.higherKey(5);        // 8  — strictly > 5

// Range view — O(1) to create, O(k + log N) to iterate
map.subMap(2, 6);        // {2=two, 4=four, 5=five}
map.headMap(5);          // {1=one, 2=two, 4=four}
map.tailMap(5);          // {5=five, 8=eight}

// Remove and return extremes
Map.Entry<Integer,String> min = map.pollFirstEntry();  // removes 1
Map.Entry<Integer,String> max = map.pollLastEntry();   // removes 8
```

---

## TreeSet — Sorted Set

`TreeSet<E>` is backed by a `TreeMap<E, Object>`. Maintains elements in sorted order,
no duplicates. All `TreeMap` navigation operations have `TreeSet` equivalents.

### Java API

```java
TreeSet<Integer> set = new TreeSet<>();
set.add(10);  set.add(5);  set.add(20);  set.add(3);

set.first();              // 3
set.last();               // 20
set.floor(7);             // 5 — largest ≤ 7
set.ceiling(7);           // 10 — smallest ≥ 7
set.lower(10);            // 5 — strictly < 10
set.higher(10);           // 20 — strictly > 10
set.pollFirst();          // removes and returns 3
set.pollLast();           // removes and returns 20

set.subSet(5, 15);        // [5, 10] — [from, to)
set.headSet(10);          // [5]     — [first, 10)
set.tailSet(10);          // [10]    — [10, last]
set.subSet(5, true, 15, true);  // [5, 10] inclusive both ends
```

### TreeSet vs TreeMap

`TreeSet<E>` ≈ `TreeMap<E, constant>`. Use TreeSet when you only need keys (membership +
ordering); use TreeMap when you need to associate a value with each key.

```java
// Frequency map with sorted keys
TreeMap<Integer, Integer> freq = new TreeMap<>();
for (int x : nums) freq.merge(x, 1, Integer::sum);

// Sorted unique values
TreeSet<Integer> unique = new TreeSet<>(Arrays.asList(nums));
```

---

## Complexity Summary

| Operation category | Time |
|-------------------|------|
| Basic get/put/remove | O(log N) |
| Min/Max (first/last) | O(log N) |
| Floor/Ceiling/Lower/Higher | O(log N) |
| SubMap creation (view) | O(1) |
| SubMap iteration (k elements) | O(k + log N) |
| Full iteration | O(N) |
| `containsValue` | O(N) |
| `size` | O(1) |

---

## TreeMap vs HashMap — When to Use Each

| Need | Use |
|------|-----|
| O(1) average get/put, no ordering | `HashMap` |
| Sorted keys, range queries, min/max, floor/ceiling | `TreeMap` |
| LRU cache, insertion-order iteration | `LinkedHashMap` |
| Concurrent sorted map | `ConcurrentSkipListMap` |
| Unique sorted elements | `TreeSet` |
| Frequency map with rank queries | `TreeMap<Integer,Integer>` |

---

## Common Interview Patterns

### Sliding Window with TreeMap (ordered multiset)
Track element counts in a window; use `firstKey()`/`lastKey()` to get current min/max.

```java
TreeMap<Integer, Integer> window = new TreeMap<>();
// add element
window.merge(val, 1, Integer::sum);
// remove element
window.merge(val, -1, Integer::sum);
if (window.get(val) == 0) window.remove(val);
// current max - min
int diff = window.lastKey() - window.firstKey();
```

**Used in:** [LongestSubarrayAbsDiff](../../challenges/leetcode/LongestContiguousSubArrayWithAbsDiffLessThanLimit/Solution.java) (the O(N log N) approach before the deque optimization)

### Event / Interval Sweeping
Sweep through sorted events using `TreeMap<time, List<event>>`:

```java
TreeMap<Integer, List<int[]>> events = new TreeMap<>();
events.computeIfAbsent(start, k -> new ArrayList<>()).add(meeting);
for (Map.Entry<Integer, List<int[]>> e : events.entrySet()) { ... }
```

### K-th Largest Element Tracking
Use a TreeMap to maintain a sorted frequency map; walk from the end to find k-th largest.
