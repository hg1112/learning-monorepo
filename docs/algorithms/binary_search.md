# Binary Search

An $O(\log N)$ algorithm to find an element in a sorted collection or solve optimization problems.

### Visual Representation (Halving the Search Space)
We maintain `lo` and `hi` pointers and repeatedly pick the middle element.

```text
Searching for X = 7 in [1, 3, 5, 7, 9, 11]

1. [ 1, 3, 5, 7, 9, 11 ]
     ^        ^      ^
    lo       mid     hi
    mid < 7 (5 < 7) -> lo = mid + 1

2. [ 1, 3, 5, 7, 9, 11 ]
              ^  ^   ^
             lo mid  hi
             mid = 7 -> Found!
```

---

## Binary Search on Answer
Used when you want to find the **best (min/max)** value that satisfies a condition.

### Key Condition
The problem must be **monotonic**:
- If `f(x)` is true, then `f(x+1)` must also be true (or always false).
- This allows us to "discard" half the search space.

### Template
```java
while (lo <= hi) {
    long mid = lo + (hi - lo) / 2;
    if (isValid(mid)) {
        ans = mid;
        hi = mid - 1; // Try better (smaller)
    } else {
        lo = mid + 1; // Try larger
    }
}
```

### Complexity
- **Time**: $O(\log(\text{Range}) \cdot \text{Cost of check()})$
- **Space**: $O(1)$
