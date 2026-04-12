# Longest Continuous Subarray With Absolute Diff Less Than or Equal to Limit

[LeetCode 1438](https://leetcode.com/problems/longest-continuous-subarray-with-absolute-diff-less-than-or-equal-to-limit/)

---

## Problem

Given an array of integers `nums` and an integer `limit`, return the size of the longest **non-empty** subarray such that the absolute difference between any two elements of this subarray is less than or equal to `limit`.

---

## Analysis of Existing Solution

### Strategy: Binary Search + TreeMap Sliding Window
The current implementation uses binary search to find the maximum possible subarray size. For each size `mid`, it performs a sliding window check using a `TreeMap` to track the current window's elements.

-   **Complexity**:
    -   Binary Search: $O(\log N)$ steps.
    -   `check()` method: $O(N \log N)$ (each map operation is $\log N$).
    -   **Total Time**: $O(N \log^2 N)$.
-   **Pros**:
    -   Logically straightforward (search for the answer).
-   **Cons**:
    -   **Slow**: $O(N \log^2 N)$ is much slower than $O(N)$.
    -   **Overhead**: `TreeMap` in Java has significant object creation overhead.
    -   **Unnecessary Complexity**: Binary search is not needed for a sliding window problem.

---

## Optimized Strategy: Monotonic Deques

Instead of binary searching, we use a single-pass sliding window with two monotonic deques to track the minimum and maximum in the current window in $O(1)$ amortized time.

### Key Insight
A subarray satisfies the condition if `max(window) - min(window) <= limit`.
1.  **`maxDeque`**: Stores indices of elements in decreasing order. `peekFirst()` is the window's maximum.
2.  **`minDeque`**: Stores indices of elements in increasing order. `peekFirst()` is the window's minimum.

### Algorithm
1.  Iterate `right` from `0` to `n-1`.
2.  Add `nums[right]` to both deques, maintaining their monotonic properties.
3.  If `maxDeque.peekFirst() - minDeque.peekFirst() > limit`:
    -   Increment `left`.
    -   Remove `left-1` from deques if it was the current min or max.
4.  Track the maximum `right - left + 1`.

### Complexity
-   **Time**: $O(N)$ (each element is added and removed from each deque at most once).
-   **Space**: $O(N)$ for the deques.

---

## Comparison Table

| Approach | Time Complexity | Space Complexity | Note |
| :--- | :--- | :--- | :--- |
| **Binary Search + TreeMap** | $O(N \log^2 N)$ | $O(N)$ | Correct but slow for large $N$. |
| **Sliding Window + TreeMap** | $O(N \log N)$ | $O(N)$ | Better, but `TreeMap` is heavy. |
| **Sliding Window + Deques** | $O(N)$ | $O(N)$ | **Optimal** performance. |
