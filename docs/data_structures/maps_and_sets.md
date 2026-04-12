# Maps & Sets

Data structures that store key-value pairs (Maps) or unique elements (Sets).
Java offers three families: hash-based, insertion-ordered, and tree-based (sorted).

---

## 1. HashMap & HashSet — Hash Table

### Internal Mechanics

A `HashMap<K,V>` is backed by an array of **buckets** (linked lists or balanced trees).

```text
key → hashCode() → compress to bucket index → store in bucket

hash("Alice") = 97365
bucket index  = 97365 % 16 = 5
bucket[5]     = [("Alice", 30)]

hash("Bob")   = 66578
bucket index  = 66578 % 16 = 2
bucket[2]     = [("Bob", 25)]
```

**Collision:** two keys hash to the same bucket — stored as a linked list (or red-black tree
when list length ≥ 8, Java 8+). Lookup is O(1) average, O(N) worst case.

### Load Factor and Rehashing

- **Default load factor:** 0.75 (resize when 75% full)
- **Resize:** double the bucket array, rehash all entries → O(N) but amortized O(1) per put
- **Initial capacity:** 16 (default). Pre-size with `new HashMap<>(expectedSize / 0.75 + 1)` to avoid rehash.

### hashCode / equals Contract

For HashMap/HashSet to work correctly, if `a.equals(b)` then `a.hashCode() == b.hashCode()`.
Violating this leads to entries being lost or doubled.

### Java API

```java
Map<String, Integer> map = new HashMap<>();
map.put("A", 1);             // O(1) avg
map.get("A");                // O(1) avg — null if absent
map.getOrDefault("B", 0);   // O(1) — safe access
map.containsKey("A");        // O(1)
map.containsValue(1);        // O(N) — no index on values
map.remove("A");             // O(1)
map.putIfAbsent("C", 3);     // O(1)
map.merge("A", 1, Integer::sum);  // increment count — O(1)
map.computeIfAbsent("list", k -> new ArrayList<>()).add(x);  // group-by pattern

// Iteration
for (Map.Entry<String,Integer> e : map.entrySet()) { ... }
for (String k : map.keySet()) { ... }
for (int v : map.values()) { ... }
map.forEach((k, v) -> ...);

Set<String> set = new HashSet<>();
set.add("A");                // O(1)
set.contains("A");           // O(1)
set.remove("A");             // O(1)
set.addAll(otherSet);        // union
set.retainAll(otherSet);     // intersection (mutates)
set.removeAll(otherSet);     // difference (mutates)
```

### Complexity

| Operation | Average | Worst |
|-----------|---------|-------|
| put / add | O(1) | O(N) — all keys collide |
| get / contains | O(1) | O(N) |
| remove | O(1) | O(N) |
| Iteration | O(N + capacity) | — |

---

## 2. LinkedHashMap & LinkedHashSet — Insertion-Ordered

A `LinkedHashMap` maintains an internal **doubly-linked list** that threads through all entries
in insertion order (or optionally access order for LRU).

```text
Bucket array: [null][Entry("A",1)][null][Entry("B",2)]
                          ↕                    ↕
Linked list:  [Head] → [Entry("A",1)] → [Entry("B",2)] → [Tail]
              (preserves insertion order)
```

### Java API

```java
Map<String, Integer> map = new LinkedHashMap<>();
// All HashMap ops at same O(1) complexity, plus:
// Iteration is guaranteed in insertion order

// Access-order LRU cache:
Map<Integer, Integer> lru = new LinkedHashMap<>(capacity, 0.75f, true) {
    protected boolean removeEldestEntry(Map.Entry<Integer,Integer> eldest) {
        return size() > capacity;
    }
};
```

**LRU cache pattern:** `LinkedHashMap` with `accessOrder=true` moves each accessed entry
to the tail, so `eldest` is always the least-recently-used entry.

---

## 3. TreeMap & TreeSet — Sorted Order

See `treemap_treeset.md` for full details on Red-Black Tree internals, all navigation
operations (floor/ceiling/lower/higher), range views, and complexity tables.

**Quick reference:**
```java
TreeMap<Integer,String> map = new TreeMap<>();
map.firstKey();    map.lastKey();       // O(log N)
map.floorKey(k);   map.ceilingKey(k);  // O(log N)
map.subMap(lo, hi);                    // O(1) view

TreeSet<Integer> set = new TreeSet<>();
set.first();  set.last();
set.floor(k); set.ceiling(k);
```

---

## 4. Summary Comparison

| Feature | HashMap/Set | LinkedHashMap/Set | TreeMap/Set |
|---------|------------|-------------------|------------|
| Internal structure | Hash table | Hash table + linked list | Red-Black tree |
| Search/Insert/Delete | O(1) avg | O(1) avg | O(log N) |
| Iteration order | Arbitrary | Insertion order | Sorted (natural/comparator) |
| Null keys | Allowed (one) | Allowed (one) | Not allowed |
| Null values | Allowed | Allowed | Allowed |
| Memory | Baseline | +linked list overhead | Higher (tree nodes) |
| floor/ceiling queries | No | No | Yes |
| Thread-safe | No | No | No |
| Concurrent alternative | ConcurrentHashMap | — | ConcurrentSkipListMap |

---

## 5. Use-Case Decision Guide

| Situation | Use |
|-----------|-----|
| Default key-value storage | `HashMap` |
| Unique element membership | `HashSet` |
| Preserve insertion order for iteration | `LinkedHashMap` |
| LRU / MRU cache with O(1) ops | `LinkedHashMap` (accessOrder=true) |
| Range queries (smallest key ≥ X) | `TreeMap` |
| Sorted unique elements | `TreeSet` |
| Frequency map needing rank queries | `TreeMap<T, Integer>` |
| Multi-value map (one key → many values) | `Map<K, List<V>>` with computeIfAbsent |
| Concurrent read-heavy map | `ConcurrentHashMap` |

---

## 6. Common Patterns

### Frequency Counting

```java
Map<Character, Integer> freq = new HashMap<>();
for (char c : s.toCharArray())
    freq.merge(c, 1, Integer::sum);    // cleaner than getOrDefault + put
```

### Grouping (Group-By)

```java
Map<Integer, List<String>> byLength = new HashMap<>();
for (String w : words)
    byLength.computeIfAbsent(w.length(), k -> new ArrayList<>()).add(w);
```

### Two-Sum Pattern

```java
Map<Integer, Integer> seen = new HashMap<>();   // value → index
for (int i = 0; i < nums.length; i++) {
    int complement = target - nums[i];
    if (seen.containsKey(complement)) return new int[]{seen.get(complement), i};
    seen.put(nums[i], i);
}
```

### Sliding Window with Frequency Map

```java
Map<Character, Integer> window = new HashMap<>();
int left = 0, valid = 0;
for (int right = 0; right < s.length(); right++) {
    char c = s.charAt(right);
    window.merge(c, 1, Integer::sum);
    if (window.get(c).equals(need.get(c))) valid++;
    while (valid == need.size()) {
        // record answer
        char out = s.charAt(left++);
        if (need.containsKey(out)) {
            if (window.get(out).equals(need.get(out))) valid--;
            window.merge(out, -1, Integer::sum);
        }
    }
}
```
