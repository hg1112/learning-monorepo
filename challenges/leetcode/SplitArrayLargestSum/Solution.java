import java.util.*;

/**
 * LeetCode 410: Split Array Largest Sum
 * 
 * This file contains three different approaches:
 * 1. Binary Search on Answer (Optimal) - O(N log(Sum))
 * 2. Top-Down Dynamic Programming (Memoization) - O(k * N^2)
 * 3. Bottom-Up Dynamic Programming (Tabulation) - O(k * N^2)
 */
class Solution {

    /**
     * 1. BINARY SEARCH ON ANSWER (MOST OPTIMAL)
     * Time: O(N log(Sum)), Space: O(1)
     */
    public int splitArray(int[] nums, int k) {
        long lo = 0, hi = 0;
        for (int num : nums) {
            lo = Math.max(lo, num);
            hi += num;
        }

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

    private boolean canSplit(int[] nums, int k, long limit) {
        int count = 1;
        long currentSum = 0;
        for (int num : nums) {
            if (currentSum + num <= limit) {
                currentSum += num;
            } else {
                count++;
                currentSum = num;
                if (count > k) return false;
            }
        }
        return true;
    }

    /**
     * 2. TOP-DOWN DYNAMIC PROGRAMMING (MEMOIZATION)
     * Time: O(k * N^2), Space: O(k * N)
     */
    public int splitArrayTopDown(int[] nums, int k) {
        int n = nums.length;
        int[][] memo = new int[n][k + 1];
        for (int[] row : memo) Arrays.fill(row, -1);

        // Prefix sum for O(1) subarray sum calculation
        int[] prefixSum = new int[n + 1];
        for (int i = 0; i < n; i++) prefixSum[i + 1] = prefixSum[i] + nums[i];

        return solve(0, k, nums, prefixSum, memo);
    }

    private int solve(int idx, int k, int[] nums, int[] prefixSum, int[][] memo) {
        int n = nums.length;
        
        // Base Case: Only 1 subarray left, must take all remaining elements
        if (k == 1) {
            return prefixSum[n] - prefixSum[idx];
        }

        if (memo[idx][k] != -1) return memo[idx][k];

        int ans = Integer.MAX_VALUE;
        // Try all possible split points for the current subarray
        // We need at least k-1 elements left for the remaining k-1 subarrays
        for (int i = idx; i <= n - k; i++) {
            int currentSum = prefixSum[i + 1] - prefixSum[idx];
            int remainingMax = solve(i + 1, k - 1, nums, prefixSum, memo);
            
            // The "heaviest bag" for this specific split point
            int maxForThisSplit = Math.max(currentSum, remainingMax);
            
            // Minimize the heaviest bag across all possible split points
            ans = Math.min(ans, maxForThisSplit);

            // Optimization: If currentSum already exceeds our best ans, stop
            if (currentSum >= ans) break;
        }

        return memo[idx][k] = ans;
    }

    /**
     * 3. BOTTOM-UP DYNAMIC PROGRAMMING (TABULATION)
     * Time: O(k * N^2), Space: O(k * N)
     */
    public int splitArrayBottomUp(int[] nums, int k) {
        int n = nums.length;
        int[] prefixSum = new int[n + 1];
        for (int i = 0; i < n; i++) prefixSum[i + 1] = prefixSum[i] + nums[i];

        // dp[j][i] = min largest sum using first i elements and j subarrays
        int[][] dp = new int[k + 1][n + 1];
        for (int[] row : dp) Arrays.fill(row, Integer.MAX_VALUE);

        // Base case: 1 subarray (k=1)
        for (int i = 1; i <= n; i++) {
            dp[1][i] = prefixSum[i];
        }

        // Fill DP table for j = 2 to k friends
        for (int j = 2; j <= k; j++) {
            // For each total number of blocks i
            for (int i = 1; i <= n; i++) {
                // Try all split points p
                for (int p = 0; p < i; p++) {
                    int currentSubarraySum = prefixSum[i] - prefixSum[p];
                    int maxLargestSum = Math.max(dp[j - 1][p], currentSubarraySum);
                    
                    dp[j][i] = Math.min(dp[j][i], maxLargestSum);
                    
                    // Optimization: if the new bag is already heavier than the best so far
                    if (currentSubarraySum >= dp[j][i]) break;
                }
            }
        }

        return dp[k][n];
    }
}
