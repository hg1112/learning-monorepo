import java.util.*;

/**
 * LeetCode 2402: Meeting Rooms III
 */
class Solution {

    /**
     * ORIGINAL IMPLEMENTATION (with identified bugs)
     */
    private class Booking {
        public long start; // BUG: Potential overflow if many delays occur
        public long duration;
        public int room;

        Booking(int room, long start, long duration) {
            this.room = room;
            this.start = start;
            this.duration = duration;
        }

        public long next() {
            if (start == -1)
                return 0;
            return start + duration;
        }

        public String toString() {
            return next() + " - " + room + " : " + start + " / " + duration;
        }
    }

    public int mostBooked(int n, int[][] meetings) {
        int ans = 0;
        int[] count = new int[n];
        PriorityQueue<Booking> unblocked = new PriorityQueue<>((a, b) -> (a.room - b.room));

        PriorityQueue<Booking> blocked = new PriorityQueue<>(
                (a, b) -> (a.next() == b.next() ? a.room - b.room : Long.compare(a.next(), b.next())));

        for (int i = 0; i < n; i++) {
            unblocked.add(new Booking(i, -1, -1));
        }

        Arrays.sort(meetings, (a, b) -> (a[0] == b[0] ? a[1] - b[1] : a[0] - b[0]));

        for (int[] meeting : meetings) {
            int start = meeting[0];
            int end = meeting[1];
            int duration = end - start;

            while (!blocked.isEmpty()) {
                Booking isFree = blocked.peek();
                if (isFree.next() <= start) {
                    unblocked.add(blocked.poll());
                } else {
                    break;
                }
            }

            Booking toBook = null;
            if (unblocked.isEmpty()) {
                toBook = blocked.poll();
                // If delayed, the meeting starts at the room's next available time
                toBook.start = toBook.next() >= start ? toBook.next() : start;
            } else {
                toBook = unblocked.poll();
                toBook.start = start;
            }
            int room = toBook.room;
            count[room]++;
            toBook.duration = duration;
            blocked.add(toBook);
        }

        int maxCount = -1;
        for (int i = 0; i < n; i++) {
            if (maxCount < count[i]) {
                ans = i;
                maxCount = count[i];
            }
        }

        return ans;
    }

    /**
     * OPTIMIZED IMPLEMENTATION (Robust version)
     * Handles:
     * 1. Integer overflow via long.
     * 2. Proper tie-breaking for room indices in occupiedRooms.
     * 3. More direct state management without custom objects.
     */
    public int mostBookedOptimized(int n, int[][] meetings) {
        Arrays.sort(meetings, (a, b) -> Integer.compare(a[0], b[0]));

        PriorityQueue<Integer> freeRooms = new PriorityQueue<>();
        for (int i = 0; i < n; i++)
            freeRooms.add(i);

        // Sort by endTime, then roomIndex (tie-breaker)
        PriorityQueue<long[]> occupiedRooms = new PriorityQueue<>((a, b) -> {
            if (a[0] != b[0])
                return Long.compare(a[0], b[0]);
            return Long.compare(a[1], b[1]);
        });

        long[] meetingCount = new long[n];

        for (int[] meeting : meetings) {
            long start = meeting[0];
            long duration = meeting[1] - start;

            while (!occupiedRooms.isEmpty() && occupiedRooms.peek()[0] <= start) {
                freeRooms.add((int) occupiedRooms.poll()[1]);
            }

            if (!freeRooms.isEmpty()) {
                int roomIdx = freeRooms.poll();
                meetingCount[roomIdx]++;
                occupiedRooms.add(new long[] { start + duration, roomIdx });
            } else {
                long[] earliest = occupiedRooms.poll();
                long finishTime = earliest[0];
                int roomIdx = (int) earliest[1];
                meetingCount[roomIdx]++;
                occupiedRooms.add(new long[] { finishTime + duration, roomIdx });
            }
        }

        int resultRoom = 0;
        for (int i = 1; i < n; i++) {
            if (meetingCount[i] > meetingCount[resultRoom]) {
                resultRoom = i;
            }
        }
        return resultRoom;
    }
}
