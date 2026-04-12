# Arrays & Lists

The foundational linear data structures for storing collections of elements.

---

## 1. Array (Fixed Size)
A contiguous block of memory. Size is fixed at creation.

### Visual Representation
```text
Index:    0      1      2      3      4
Value: [ 10 ][ 20 ][ 30 ][ 40 ][ 50 ]
Address: 100    104    108    112    116  (Assuming 4-byte ints)
```

### Java Usage
```java
int[] arr = new int[5];
arr[0] = 10;
int length = arr.length;
```

---

## 2. ArrayList (Dynamic Array)
A wrapper around a standard array that automatically resizes itself when full (usually by 1.5x or 2x).

### Mechanics
1.  **Append**: $O(1)$ amortized.
2.  **Resize**: When full, a new larger array is created and elements are copied ($O(N)$).
3.  **Random Access**: $O(1)$.

### Java API
```java
List<String> list = new ArrayList<>();
list.add("A");          // Append
list.get(0);            // Access by index
list.set(0, "B");       // Update
list.remove(list.size() - 1); // Remove last (O(1))
list.remove(0);         // Remove first (O(N) due to shifting)
```

---

## 3. LinkedList (Doubly Linked)
A sequence of nodes where each node points to the next and previous nodes.

### Visual Representation
```text
      Head                                          Tail
        |                                             |
[Prev|10|Next] <-> [Prev|20|Next] <-> [Prev|30|Next] <-> [Prev|40|Next]
```

### Java API
```java
LinkedList<Integer> list = new LinkedList<>();
list.addFirst(5);       // O(1)
list.addLast(10);       // O(1)
list.removeFirst();     // O(1)
list.get(2);            // O(N) - Must traverse from head/tail
```

---

## Performance Comparison

| Operation | Array / ArrayList | LinkedList |
| :--- | :--- | :--- |
| **Access (get index)** | $O(1)$ | $O(N)$ |
| **Insert/Delete (Start)** | $O(N)$ | $O(1)$ |
| **Insert/Delete (End)** | $O(1)$ Amortized | $O(1)$ |
| **Insert/Delete (Middle)** | $O(N)$ | $O(N)$ (to find node) |
| **Memory Overhead** | Low | High (due to pointers) |
