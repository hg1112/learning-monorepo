import java.util.*;

class Solution {

    private final int[] data;
    private final int length;

    public Solution(int[] nums) {
        data = nums;
        length = nums.length;

    }

    public int[] reset() {
        return data.clone();

    }

    private void swap(int[] arr, int i, int j) {
        int temp = arr[i];
        arr[i] = arr[j];
        arr[j] = temp;
    }

    public int[] shuffle() {
        int[] ans = data.clone();
        Random random = new Random();
        for (int i = 0; i < length; i++) {
            int nextAfter = random.nextInt(length - i);
            swap(ans, i, i + nextAfter);
        }
        return ans;
    }
}
