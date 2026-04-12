# Arrays & Lists

The foundational linear data structures for storing collections of elements.

---

## 1. Array (Fixed Size)

A contiguous block of memory. Size is fixed at creation; elements accessed in O(1) by index.

### Memory Layout

```text
Index:    0      1      2      3      4
Value: [ 10 ][ 20 ][ 30 ][ 40 ][ 50 ]
Addr:   100    104    108    112    116   (4-byte ints)

address(i) = base_address + i × element_size
```

### Java Usage

```java
int[] arr = new int[5];
arr[0] = 10;
int len = arr.length;          // O(1)

// 2D array
int[][] grid = new int[m][n];  // m rows, n cols — row-major layout
Arrays.fill(arr, 0);           // O(N) fill
Arrays.sort(arr);              // O(N log N) — dual-pivot quicksort in JDK
Arrays.copyOfRange(arr, 1, 4); // O(k) — new array, indices [1,4)
```

### Complexity

| Operation | Time | Note |
|-----------|------|------|
| Access by index | O(1) | Direct address calculation |
| Search (unsorted) | O(N) | Linear scan |
| Search (sorted) | O(log N) | Binary search |
| Insert/delete at end | O(1) | Just write + update length |
| Insert/delete at middle | O(N) | Must shift elements |

---

## 2. ArrayList (Dynamic Array)

A `java.util.ArrayList<E>` wraps a raw array and automatically resizes.

### Resize Mechanics

1. Initial capacity: 10 (default)
2. When full, allocate a new array of `capacity × 1.5` and copy all elements
3. **Amortized O(1) append:** copying is O(N) but happens rarely — averaged across N appends, cost per append is O(1)

```text
cap=4: [A, B, C, D]  — full
add E: new array cap=6, copy [A,B,C,D], add E → [A,B,C,D,E,_]
```

### Java API

```java
List<String> list = new ArrayList<>();
list.add("A");              // append — O(1) amortized
list.add(0, "X");           // insert at index — O(N) (shifts right)
list.get(2);                // O(1)
list.set(2, "Z");           // O(1)
list.remove(list.size()-1); // remove last — O(1)
list.remove(0);             // remove first — O(N)
list.size();                // O(1)
list.contains("A");         // O(N)
Collections.sort(list);     // O(N log N)

// Pre-size to avoid resizing
List<Integer> sized = new ArrayList<>(n);
```

---

## 3. LinkedList (Doubly Linked)

A sequence of nodes; each node holds a value plus `prev` and `next` pointers.

### Memory Layout

```text
      Head                                          Tail
        |                                             |
[Prev|10|Next] ↔ [Prev|20|Next] ↔ [Prev|30|Next] ↔ [Prev|40|Next]
```

### Java API

```java
LinkedList<Integer> ll = new LinkedList<>();
ll.addFirst(5);    // O(1) — prepend
ll.addLast(10);    // O(1) — append
ll.removeFirst();  // O(1)
ll.removeLast();   // O(1)
ll.get(2);         // O(N) — traverse from head or tail (whichever is closer)
ll.size();         // O(1) — field
```

### Performance Comparison

| Operation | Array/ArrayList | LinkedList |
|-----------|----------------|------------|
| Access by index | O(1) | O(N) |
| Insert/delete at head | O(N) | O(1) |
| Insert/delete at tail | O(1) amortized | O(1) |
| Insert/delete at middle | O(N) | O(N) to find + O(1) to splice |
| Memory overhead | Low (no pointers) | High (2 pointers + object header per node) |
| Cache locality | Excellent | Poor (nodes scattered in heap) |

**When to use LinkedList over ArrayList:** almost never in practice. Use `ArrayDeque` when
you need O(1) head and tail operations. LinkedList's random-access is O(N) which kills
most algorithms.

---

## 4. Common Array Patterns

### Two Pointers

Use two indices moving toward each other (or in the same direction) to solve problems
in O(N) that would naively require O(N²).

**Opposite ends — sorted array target sum:**
```java
int left = 0, right = nums.length - 1;
while (left < right) {
    int sum = nums[left] + nums[right];
    if      (sum == target) return new int[]{left, right};
    else if (sum < target)  left++;
    else                    right--;
}
```

**Fast/slow (Floyd's cycle detection):**
```java
int slow = 0, fast = 0;
while (fast < nums.length && fast + 1 < nums.length) {
    slow = nums[slow];
    fast = nums[nums[fast]];
    if (slow == fast) { /* cycle detected */ break; }
}
```

---

### Sliding Window

Maintain a contiguous subarray `[left, right]`. Expand right, shrink left when
a constraint is violated.

**Fixed-size window:**
```java
int windowSum = 0;
for (int i = 0; i < k; i++) windowSum += nums[i];
int maxSum = windowSum;
for (int i = k; i < nums.length; i++) {
    windowSum += nums[i] - nums[i - k];  // add right, drop left
    maxSum = Math.max(maxSum, windowSum);
}
```

**Variable-size window (expand right, shrink left on violation):**
```java
int left = 0, maxLen = 0;
// state to track constraint (e.g., character count map)
for (int right = 0; right < s.length(); right++) {
    // add s[right] to state
    while (/* constraint violated */) {
        // remove s[left] from state
        left++;
    }
    maxLen = Math.max(maxLen, right - left + 1);
}
```

---

### Prefix Sum

Precompute cumulative sums to answer range-sum queries in O(1) after O(N) preprocessing.

```java
int[] prefix = new int[n + 1];
for (int i = 0; i < n; i++) prefix[i+1] = prefix[i] + nums[i];

// sum of nums[l..r] (0-indexed, inclusive)
int rangeSum = prefix[r+1] - prefix[l];
```

**2D prefix sum:**
```java
int[][] pre = new int[m+1][n+1];
for (int i = 1; i <= m; i++)
    for (int j = 1; j <= n; j++)
        pre[i][j] = nums[i-1][j-1] + pre[i-1][j] + pre[i][j-1] - pre[i-1][j-1];
// sum of rectangle (r1,c1)→(r2,c2):
int sum = pre[r2+1][c2+1] - pre[r1][c2+1] - pre[r2+1][c1] + pre[r1][c1];
```

---

### Kadane's Algorithm (Maximum Subarray)

```java
int maxSum = nums[0], current = nums[0];
for (int i = 1; i < nums.length; i++) {
    current = Math.max(nums[i], current + nums[i]);  // extend or restart
    maxSum  = Math.max(maxSum, current);
}
```
O(N) time, O(1) space.

---

## 5. String & StringBuilder

Strings are immutable in Java. String concatenation in a loop is O(N²) — use `StringBuilder`.

```java
// BAD — O(N²) due to object creation
String s = "";
for (String part : parts) s += part;

// GOOD — O(N) amortized
StringBuilder sb = new StringBuilder();
for (String part : parts) sb.append(part);
String result = sb.toString();
```

### Useful String Methods

```java
s.charAt(i)           // O(1)
s.length()            // O(1)
s.substring(l, r)     // O(r-l) — creates a new String
s.toCharArray()       // O(N)
s.indexOf("abc")      // O(N×M) — naive
s.split(",")          // O(N) — returns String[]
s.trim()              // O(N) — strips whitespace
String.valueOf(42)    // int → String
Integer.parseInt("42") // String → int
char[] ca = s.toCharArray(); Arrays.sort(ca); new String(ca);  // sort string
```

### Character Operations

```java
Character.isLetter(c)
Character.isDigit(c)
Character.toLowerCase(c)
Character.toUpperCase(c)
c - 'a'              // 0-indexed offset for lowercase letter
```

---

## Complexity Quick Reference

| Structure | Access | Search | Insert head | Insert tail | Insert mid | Space |
|-----------|--------|--------|------------|------------|-----------|-------|
| Array | O(1) | O(N) / O(log N) sorted | O(N) | O(1) | O(N) | O(N) |
| ArrayList | O(1) | O(N) | O(N) | O(1) amort | O(N) | O(N) |
| LinkedList | O(N) | O(N) | O(1) | O(1) | O(N) find | O(N) |
