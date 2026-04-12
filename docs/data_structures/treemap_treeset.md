# TreeSet & TreeMap

In Java, these are implemented using **Red-Black Trees** (Self-balancing Binary Search Trees). They maintain elements in sorted order.

### Visual Representation (Sorted BST)
Elements are automatically kept in order: `Left < Root < Right`.

```text
       [ 15 ]           <-- Root
      /      \
   [ 8 ]    [ 24 ]      <-- 8 < 15, 24 > 15
   /   \    /    \
 [3]  [10] [18]  [30]
```

### Key Properties
- **Time Complexity**: $O(\log N)$ for `add`, `remove`, `contains`, `first`, `last`, `ceiling`, `floor`.
- **Space Complexity**: $O(N)$
- **Order**: Always maintains natural order (or a custom `Comparator`).

### Key Methods (Java)
- `firstKey()` / `lastKey()`: Min/Max elements.
- `ceilingKey(X)`: Smallest key $\ge X$.
- `floorKey(X)`: Largest key $\le X$.
- `higherKey(X)`: Smallest key $> X$.
- `lowerKey(X)`: Largest key $< X$.
