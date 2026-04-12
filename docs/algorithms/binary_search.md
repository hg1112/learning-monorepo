# Binary Search

An O(log N) algorithm to find a target in a sorted collection or to optimize over a
monotonic search space. Two distinct modes: **search** and **search on answer**.

---

## 1. Classic Binary Search (Find a Value)

### Visual — Halving the Search Space

```text
Searching for 7 in [1, 3, 5, 7, 9, 11]:

Step 1: lo=0 hi=5 mid=2  nums[2]=5 < 7 → lo=3
Step 2: lo=3 hi=5 mid=4  nums[4]=9 > 7 → hi=3
Step 3: lo=3 hi=3 mid=3  nums[3]=7 == 7 → found!
```

### Iterative Template

```java
public int binarySearch(int[] nums, int target) {
    int lo = 0, hi = nums.length - 1;
    while (lo <= hi) {
        int mid = lo + (hi - lo) / 2;   // avoids overflow vs (lo+hi)/2
        if (nums[mid] == target) return mid;
        if (nums[mid] < target)  lo = mid + 1;
        else                     hi = mid - 1;
    }
    return -1;  // not found
}
```

### Find Leftmost / Rightmost Occurrence

```java
// Leftmost index where nums[i] >= target (first occurrence of target if present)
public int lowerBound(int[] nums, int target) {
    int lo = 0, hi = nums.length;
    while (lo < hi) {
        int mid = lo + (hi - lo) / 2;
        if (nums[mid] < target) lo = mid + 1;
        else                    hi = mid;
    }
    return lo;  // lo == hi == first index >= target
}

// Rightmost index where nums[i] <= target (last occurrence of target if present)
public int upperBound(int[] nums, int target) {
    int lo = 0, hi = nums.length;
    while (lo < hi) {
        int mid = lo + (hi - lo) / 2;
        if (nums[mid] <= target) lo = mid + 1;
        else                     hi = mid;
    }
    return lo - 1;  // last index <= target
}
```

### Complexity

| | Time | Space |
|-|------|-------|
| Binary search | O(log N) | O(1) |
| Recursive variant | O(log N) | O(log N) stack |

---

## 2. Binary Search on Answer

Use when the answer is a numeric value in a range `[lo, hi]` and the problem has
**monotonicity**: if answer `x` is feasible, then `x+1` is also feasible
(or vice versa — the predicate is monotonic).

### When to Apply

1. The problem asks for a **minimum value that makes something possible** (or maximum)
2. You can write a `check(x)` function that answers "is `x` feasible?" in polynomial time
3. `check` is monotonic: all `true` values are contiguous, all `false` values are contiguous

### Template

```java
// Minimize x such that check(x) is true
long lo = /* minimum possible answer */;
long hi = /* maximum possible answer */;
long ans = hi;

while (lo <= hi) {
    long mid = lo + (hi - lo) / 2;
    if (check(mid)) {
        ans = mid;    // mid is feasible — try smaller
        hi = mid - 1;
    } else {
        lo = mid + 1; // mid too small — try larger
    }
}
return ans;
```

---

## 3. Worked Example — Split Array Largest Sum

**LeetCode 410** | [Solution.java](../../challenges/leetcode/SplitArrayLargestSum/Solution.java)

**Problem:** Split `nums` into k non-empty contiguous subarrays. Minimize the maximum subarray sum.

### Why Binary Search Works Here

The problem has **monotonicity**:
- If we can split into k groups each with sum ≤ `limit`, we can also do it with `limit+1`
- If we cannot do it with `limit`, we cannot do it with any smaller limit

Search space: `[max(nums), sum(nums)]`
- `lo = max(nums)` — someone must carry the largest single element
- `hi = sum(nums)` — one person carries everything

### Check Function

"Can we split `nums` into **at most** k groups with each group sum ≤ `limit`?"

```java
boolean canSplit(int[] nums, int k, long limit) {
    int groups = 1;
    long current = 0;
    for (int num : nums) {
        if (current + num > limit) {
            groups++;      // start a new group
            current = 0;
            if (groups > k) return false;
        }
        current += num;
    }
    return true;
}
```

### Full Solution

```java
public int splitArray(int[] nums, int k) {
    long lo = 0, hi = 0;
    for (int n : nums) { lo = Math.max(lo, n); hi += n; }

    long ans = hi;
    while (lo <= hi) {
        long mid = lo + (hi - lo) / 2;
        if (canSplit(nums, k, mid)) {
            ans = mid;
            hi = mid - 1;
        } else {
            lo = mid + 1;
        }
    }
    return (int) ans;
}
// Time: O(N log(Sum)), Space: O(1)
```

**Trace on `nums=[7,2,5,10,8], k=2`:**
```
lo=10, hi=32

mid=21: canSplit? [7,2,5|10,8] groups=2 ≤ 2 → feasible. ans=21, hi=20
mid=15: canSplit? [7,2,5|10|8] groups=3 > 2 → infeasible. lo=16
mid=18: canSplit? [7,2,5|10,8] groups=2 ≤ 2 → feasible. ans=18, hi=17
mid=16: canSplit? [7,2,5|10|8] groups=3 > 2 → infeasible. lo=17
mid=17: canSplit? [7,2,5|10|8] → wait: 7+2+5=14≤17, +10=24>17→new group, 10≤17, +8=18>17→new group. groups=3>2 → infeasible. lo=18
lo > hi → answer = 18
```

### Binary Search vs Dynamic Programming

| Feature | Binary Search on Answer | Dynamic Programming |
|---------|------------------------|---------------------|
| Time | O(N log Sum) | O(k × N²) |
| Space | O(1) | O(N × k) |
| Requires | Monotonicity of check | Optimal substructure |
| Flexibility | Rigid — check must be yes/no | High — change cost function easily |

**When to prefer DP:** if the cost function is not monotonic (e.g., minimize the
maximum of `sum²` per group — same structure but `check` now depends on a non-linear
cost, and binary search no longer applies cleanly).

**DP recurrence** (for reference):
```
dp[i][j] = min over p in [0,i-1] of:  max(dp[p][j-1], sum(nums[p..i-1]))
```

---

## 4. More Binary Search on Answer Examples

| Problem | lo | hi | check |
|---------|----|----|-|
| Koko Eating Bananas (LC 875) | 1 | max(piles) | Can eat all piles in H hours at speed k? |
| Capacity to Ship Packages (LC 1011) | max(weight) | sum(weight) | Can ship all in D days at capacity k? |
| Minimum Days to Make Bouquets (LC 1482) | 1 | max(bloomDay) | Can make m bouquets after k days? |
| Find Kth Smallest Pair Distance (LC 719) | 0 | max-min | Are there ≥ k pairs with distance ≤ k? |

---

## 5. Off-by-One Pitfalls

```java
// ✓ Correct: mid = lo + (hi - lo) / 2   avoids integer overflow
// ✗ Wrong:   mid = (lo + hi) / 2        overflows when lo+hi > Integer.MAX_VALUE

// ✓ Correct loop condition for exact search:      lo <= hi
// ✓ Correct loop condition for bound-finding:     lo < hi  (lo==hi is the answer)

// For "find minimum x where check(x)=true":
//   if check(mid): ans=mid; hi=mid-1  (try smaller)
//   else:          lo=mid+1           (too small)
```

---

## Complexity

| Variant | Time | Space |
|---------|------|-------|
| Classic search | O(log N) | O(1) |
| Binary search on answer | O(log(Range) × check cost) | O(1) |
| check cost (canSplit style) | O(N) | O(1) |
| Combined (Split Array) | O(N log Sum) | O(1) |
