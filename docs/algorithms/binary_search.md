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

### Iterative Implementation
The standard and most memory-efficient way using a `while` loop.

```java
public int binarySearchIterative(int[] nums, int target) {
    int lo = 0, hi = nums.length - 1;
    while (lo <= hi) {
        int mid = lo + (hi - lo) / 2;
        if (nums[mid] == target) return mid;
        if (nums[mid] < target) lo = mid + 1;
        else hi = mid - 1;
    }
    return -1;
}
```

### Recursive Implementation
Uses the call stack to manage the search space. Useful for divide-and-conquer logic.

```java
public int binarySearchRecursive(int[] nums, int target, int lo, int hi) {
    if (lo > hi) return -1;
    
    int mid = lo + (hi - lo) / 2;
    if (nums[mid] == target) return mid;
    
    if (nums[mid] < target) 
        return binarySearchRecursive(nums, target, mid + 1, hi);
    else 
        return binarySearchRecursive(nums, target, lo, mid - 1);
}
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
