class Solution {
    public int minOperations(int n) {
	int ans = 0;
        while (n != 0) {
		if (n & 1 == 1) ans += 1;
		n = n >> 1;
	}
	return ans;
    }
}
