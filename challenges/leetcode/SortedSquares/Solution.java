class Solution {

    public int[] sortedSquares(int[] nums) {
        int n = nums.length;

        if (n == 0)
            return nums;

        int start = -1, left = 0, right = 0;
        for (int i = 0; i < n; i++) {
            start = i;
            if (nums[i] >= 0)
                break;
        }

        left = start - 1;
        right = start;

        int[] ans = new int[n];
        int idx = 0;
        while (idx < n) {
            int valleft = -1;
            if (left >= 0 && left < n)
                valleft = nums[left] * nums[left];
            int valright = -1;
            if (right >= 0 && right < n)
                valright = nums[right] * nums[right];

            System.out.println(left + " " + valleft + " " + right + " " + valright);

            if (valleft == -1) {
                ans[idx] = valright;
                right++;
            } else if (valright == -1) {
                ans[idx] = valleft;
                left--;
            } else if (valleft >= valright) {
                ans[idx] = valright;
                right++;
            } else {
                ans[idx] = valleft;
                left--;
            }

            idx++;
        }

        return ans;
    }
}
