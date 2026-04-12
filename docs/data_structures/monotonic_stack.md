# Monotonic Stack

A stack that enforces a strict ordering invariant (non-increasing or non-decreasing).
When a new element violates the invariant, elements are popped until it no longer does.
Each element is pushed and popped at most once → O(N) total for a full pass.

---

## Variants

| Variant | Stack order (bottom → top) | Front (top) is |
|---------|--------------------------|----------------|
| Monotonic increasing | `[2, 5, 8]` | current smallest seen so far |
| Monotonic decreasing | `[9, 6, 3]` | current largest seen so far |

"Increasing" means stack values go up from bottom to top — so when you push `x`,
you pop everything **≥ x** (for strict) or **> x** (for non-strict).

---

## Core Template

### Next Greater Element (NGE) — monotonic decreasing stack

```java
int[] nge = new int[n];
Arrays.fill(nge, -1);              // default: no greater element
Deque<Integer> stack = new ArrayDeque<>();  // stores indices

for (int i = 0; i < n; i++) {
    // while top of stack has a smaller value, nums[i] is its NGE
    while (!stack.isEmpty() && nums[stack.peekFirst()] < nums[i]) {
        nge[stack.pollFirst()] = nums[i];
    }
    stack.addFirst(i);
}
```

**Invariant:** stack always contains indices whose NGE has not yet been found,
in decreasing order of value (so top is the smallest "unsolved" index).

### Next Smaller Element (NSE) — monotonic increasing stack

```java
int[] nse = new int[n];
Arrays.fill(nse, -1);
Deque<Integer> stack = new ArrayDeque<>();

for (int i = 0; i < n; i++) {
    while (!stack.isEmpty() && nums[stack.peekFirst()] > nums[i]) {
        nse[stack.pollFirst()] = nums[i];
    }
    stack.addFirst(i);
}
```

### Previous Greater / Previous Smaller

Process right-to-left, or push before checking:

```java
// Previous greater element (what is the last bar taller than me, to my left?)
Deque<Integer> stack = new ArrayDeque<>();
for (int i = 0; i < n; i++) {
    while (!stack.isEmpty() && nums[stack.peekFirst()] <= nums[i])
        stack.pollFirst();
    pge[i] = stack.isEmpty() ? -1 : nums[stack.peekFirst()];
    stack.addFirst(i);
}
```

---

## Use Cases

### 1. Next Greater Element

```
nums = [2, 1, 2, 4, 3]
NGE  = [4, 2, 4,-1,-1]
```

**Trace:**
```
i=0 (val:2): stack empty → push 0.       stack:[0]
i=1 (val:1): 2>1 → no pop → push 1.     stack:[0,1]
i=2 (val:2): 1<2 → pop 1, nge[1]=2.
             2==2 → no pop → push 2.     stack:[0,2]
i=3 (val:4): 2<4 → pop 2, nge[2]=4.
             2<4 → pop 0, nge[0]=4.
             stack empty → push 3.       stack:[3]
i=4 (val:3): 4>3 → no pop → push 4.     stack:[3,4]
End: remaining indices [3,4] → nge=-1
```

---

### 2. Largest Rectangle in Histogram

**LeetCode 84** — Find the area of the largest rectangle that can be formed in the histogram.

**Key insight:** For each bar, find the first shorter bar to its left and right.
The area using bar `i` as the shortest bar = `height[i] × (right[i] − left[i] − 1)`.

```java
public int largestRectangleArea(int[] heights) {
    int n = heights.length, maxArea = 0;
    Deque<Integer> stack = new ArrayDeque<>();
    // extend with sentinel 0-height bars on both ends
    int[] h = new int[n + 2];
    h[0] = h[n + 1] = 0;
    System.arraycopy(heights, 0, h, 1, n);
    n += 2;

    for (int i = 0; i < n; i++) {
        while (!stack.isEmpty() && h[stack.peekFirst()] > h[i]) {
            int height = h[stack.pollFirst()];
            int width  = i - stack.peekFirst() - 1;   // right boundary - left boundary - 1
            maxArea = Math.max(maxArea, height * width);
        }
        stack.addFirst(i);
    }
    return maxArea;
}
```

**Trace: heights = [2, 1, 5, 6, 2, 3]**
```
Stack stores indices of bars in increasing height order.
When a shorter bar arrives, pop and compute area using popped bar as the height.
The "width" is bounded by the new bar on the right and the current stack top on the left.

Best area: height=5 (index 2), width=2 (indices 2–3) → 10
         + height=6 (index 3), width=1 → 6
         + height=2, width=5 → 10
         + height=3, width=1 → 3
Result: 10
```

**Complexity:** O(N) time, O(N) space.

---

### 3. Trapping Rain Water

**LeetCode 42** — How much water can be trapped between bars after rain?

**Monotonic Stack approach:** When the current bar is taller than the top of the stack,
water fills the "bowl" between the popped bar, the current bar, and the new stack top.

```java
public int trap(int[] height) {
    int total = 0;
    Deque<Integer> stack = new ArrayDeque<>();

    for (int i = 0; i < height.length; i++) {
        while (!stack.isEmpty() && height[stack.peekFirst()] < height[i]) {
            int bottom = stack.pollFirst();
            if (stack.isEmpty()) break;
            int left  = stack.peekFirst();
            int width = i - left - 1;
            int depth = Math.min(height[left], height[i]) - height[bottom];
            total += width * depth;
        }
        stack.addFirst(i);
    }
    return total;
}
```

**Trace: height = [0, 1, 0, 2, 1, 0, 1, 3, 2, 1, 2, 1]**
```
Water trapped = 6
Bowl 1: between index 1 (h=1) and index 3 (h=2) → 1 unit
Bowl 2: between index 3 (h=2) and index 7 (h=3) → 4 units
Bowl 3: between index 7 (h=3) and index 10 (h=2) → 1 unit
```

**Two-pointer alternative:** O(N) time, O(1) space — generally preferred.

---

### 4. Sum of Subarray Minimums

**LeetCode 907** — For each subarray, add its minimum to the total.

**Key:** For each element `A[i]`, count how many subarrays have `A[i]` as the minimum.
Use previous smaller element (PSE) and next smaller element (NSE):
- `left[i]` = distance from i to PSE (number of subarrays where A[i] is min from left side)
- `right[i]` = distance from i to NSE

Contribution of `A[i]` = `A[i] × left[i] × right[i]`

```java
public int sumSubarrayMins(int[] arr) {
    int n = arr.length;
    long MOD = 1_000_000_007L, ans = 0;
    int[] left = new int[n], right = new int[n];
    Deque<Integer> stack = new ArrayDeque<>();

    // previous smaller (strict)
    for (int i = 0; i < n; i++) {
        while (!stack.isEmpty() && arr[stack.peekFirst()] >= arr[i]) stack.pollFirst();
        left[i] = stack.isEmpty() ? i + 1 : i - stack.peekFirst();
        stack.addFirst(i);
    }
    stack.clear();
    // next smaller or equal (non-strict — avoids double counting)
    for (int i = n - 1; i >= 0; i--) {
        while (!stack.isEmpty() && arr[stack.peekFirst()] > arr[i]) stack.pollFirst();
        right[i] = stack.isEmpty() ? n - i : stack.peekFirst() - i;
        stack.addFirst(i);
    }
    for (int i = 0; i < n; i++)
        ans = (ans + (long) arr[i] * left[i] * right[i]) % MOD;
    return (int) ans;
}
```

---

## Monotonic Stack vs Monotonic Deque

| Aspect | Monotonic Stack | Monotonic Deque |
|--------|----------------|----------------|
| Data structure | Stack (one end) | Deque (both ends) |
| Pop from | Top only | Both front (eviction) + back (maintain order) |
| Primary use | Find NGE / NSE across entire array | Sliding window min/max |
| Window? | No | Yes — front eviction removes stale indices |
| Java class | `ArrayDeque` used as stack | `ArrayDeque` used as deque |

See `queues_and_stacks.md` for the Monotonic Deque pattern with the Longest Subarray worked example.

---

## Complexity

All monotonic stack algorithms process each element at most twice (one push + one pop):

| Algorithm | Time | Space |
|-----------|------|-------|
| Next Greater / Smaller Element | O(N) | O(N) |
| Largest Rectangle in Histogram | O(N) | O(N) |
| Trapping Rain Water (stack) | O(N) | O(N) |
| Sum of Subarray Minimums | O(N) | O(N) |
| Sliding Window Min/Max (deque) | O(N) | O(N) |

---

## Interview Checklist

- Can you identify that "next greater/smaller" or "previous greater/smaller" is the key subproblem?
- Are you storing **indices** in the stack (not values) so you can compute widths/distances?
- For histograms: sentinel bars (height 0) at both ends simplify edge-case handling.
- For duplicate values: decide whether PSE/NSE is strict (`<`) or non-strict (`≤`) to avoid double-counting.
