class Solution {
    public int splitArray(int[] nums, int k) {

        int n = nums.length;
        int[] prefixSum = new int[n + 1];
        prefixSum[0] = 0;
        for (int i = 1; i <= n; i++) {
            prefixSum[i] = prefixSum[i - 1] + nums[i - 1];
        }
    }

    private boolean check(int[] nums, int[] prefixSum, int k) {
    }

    public int floor(int[] arr, int target) {
        int lo = 0;
        int hi = arr.length - 1;

        while (lo <= hi) {

        }
    }

}
