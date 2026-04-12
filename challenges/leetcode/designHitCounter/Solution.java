import java.util.*;

class HitCounter {


    private final int[] times;
    private final int[] hits;

    public HitCounter() {
        times = new int[300];
        hits = new int[300];
    }

    public void hit(int timestamp) {
        int idx = timestamp % 300;
        if (times[idx] == timestamp)
            hits[idx]++;
        else {
            times[idx] = timestamp;
            hits[idx] = 1;
        }
    }

    public int getHits(int timestamp) {
        int ans = 0;
        for (int i = 0 ; i < 300; i++) {
            if (timestamp - times[i] + 1 <= 300) ans += hits[i];
        }
        return ans;
    }
}
