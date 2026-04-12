import java.util.*;

/**
 * LeetCode 1438: Longest Continuous Subarray With Absolute Diff Less Than or Equal to Limit
 */
class Solution {

    /**
     * EXISTING SOLUTION: Binary Search + TreeMap Sliding Window
     * 
     * Complexity Analysis:
     * - Time: O(N log N * log N) = O(N log^2 N).
     *   The binary search adds a log N factor, and the TreeMap check adds another log N factor.
     * - Space: O(N) for the TreeMap.
     */
    private boolean check(int[] nums, int limit, int size) {
        int n = nums.length;
        TreeMap<Integer, Integer> map = new TreeMap<>();
        int left = 0, right = 0;
        while (right < n) {

            map.put(nums[right], map.getOrDefault(nums[right], 0) + 1);

            while (right - left + 1 > size) {
                int k = nums[left];
                map.put(k, map.get(k) - 1);
                if (map.get(k) == 0)
                    map.remove(k);
                left++;
            }

            if ((right - left + 1) == size) {
                // System.out.println(map + " " + size + " " + left + " - " + right);
                int max = map.lastKey();
                int min = map.firstKey();
                if (max - min <= limit)
                    return true;
            }

            right++;
        }
        return false;
    }

    public int longestSubarray(int[] nums, int limit) {
        int n = nums.length;
        int lo = 1;
        int hi = n;

        int ans = -1;
        while (lo <= hi) {
            int mid = lo + ((hi - lo) >> 1);
            if (check(nums, limit, mid)) {
                ans = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return ans;
    }

    /**
     * OPTIMIZED SOLUTION: Single-pass Sliding Window + Monotonic Deques
     * 
     * Complexity Analysis:
     * - Time: O(N). Each element is added/removed from each deque at most once.
     * - Space: O(N) for the deques.
     */
    public int longestSubarrayOptimized(int[] nums, int limit) {
        int n = nums.length;
        if (n == 0) return 0;

        // Monotonic deques for max and min values in the current window
        Deque<Integer> maxDeque = new ArrayDeque<>();
        Deque<Integer> minDeque = new ArrayDeque<>();

        int left = 0;
        int maxLen = 0;

        for (int right = 0; right < n; right++) {
            // Maintain decreasing maxDeque
            while (!maxDeque.isEmpty() && nums[maxDeque.peekLast()] < nums[right]) {
                maxDeque.pollLast();
            }
            maxDeque.addLast(right);

            // Maintain increasing minDeque
            while (!minDeque.isEmpty() && nums[minDeque.peekLast()] > nums[right]) {
                minDeque.pollLast();
            }
            minDeque.addLast(right);

            // Shrink window from the left if absolute difference exceeds limit
            while (!maxDeque.isEmpty() && !minDeque.isEmpty() && 
                   nums[maxDeque.peekFirst()] - nums[minDeque.peekFirst()] > limit) {
                left++;
                if (maxDeque.peekFirst() < left) maxDeque.pollFirst();
                if (minDeque.peekFirst() < left) minDeque.pollFirst();
            }

            maxLen = Math.max(maxLen, right - left + 1);
        }

        return maxLen;
    }
}
