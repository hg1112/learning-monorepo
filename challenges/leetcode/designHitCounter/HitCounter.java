import java.util.*;

class HitCounter {

	TreeMap<Integer, Integer> counter ;

    public HitCounter() {
	counter = new TreeMap<>();
    }
    
    public void hit(int timestamp) {
	counter.put(timestamp, counter.getOrDefault(timestamp, 0) + 1);
    }
    
    public int getHits(int timestamp) {
       int prev = timestamp - 5;
	TreeMap<Integer, Integer> boundedMap = counter.subMap(prev, true, timestamp, true);
	int sum = 0;
	for (Integer value: boundedMap.values()) sum += value;
	return sum;
    }
}

/**
 * Your HitCounter object will be instantiated and called as such:
 * HitCounter obj = new HitCounter();
 * obj.hit(timestamp);
 * int param_2 = obj.getHits(timestamp);
 */
