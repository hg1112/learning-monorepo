# Meeting Rooms III

[LeetCode 2402](https://leetcode.com/problems/meeting-rooms-iii/)

---

## Problem

You are given an integer `n` (number of rooms) and a 2D array `meetings` where `meetings[i] = [start, end]`.
- All `start` times are unique.
- When a meeting begins, it is assigned to the **lowest-indexed available room**.
- If no rooms are available, the meeting is delayed until the earliest room becomes free.
- The duration of the meeting remains the same.

Return the index of the room that held the most meetings. If multiple rooms have the same count, return the lowest-indexed room.

---

## Strategy: Two Min-Heaps

### Key Insight
We need to efficiently track two things:
1.  **Which rooms are currently free?** (Always pick the lowest index).
2.  **When will occupied rooms become free?** (Always pick the earliest end time).

### Heaps
-   **`freeRooms` (PriorityQueue<Integer>):** Stores indices of available rooms. Sorted by **index**.
-   **`occupiedRooms` (PriorityQueue<long[]>):** Stores `[endTime, roomIndex]` of meetings in progress. Sorted by **endTime**, then **roomIndex**.

### Steps
1.  **Sort Meetings:** Sort by `start` time.
2.  **For each meeting `[start, end]`:**
    -   Free up all rooms in `occupiedRooms` whose `endTime <= start`. Move their indices to `freeRooms`.
    -   **Case A: A room is available.**
        -   Pick the lowest index from `freeRooms`.
        -   Start the meeting at `start` and end at `end`.
    -   **Case B: No rooms are available.**
        -   Wait for the earliest room in `occupiedRooms` to finish.
        -   The room's new `endTime` becomes `earliestEndTime + duration`.
        -   The room index remains the same.
    -   **Update Count:** Increment the meeting count for the chosen room.

3.  **Find Result:** Iterate through the count array and return the index with the maximum count.

### Complexity
-   **Time:** $O(M \log M + M \log N)$ where $M$ is the number of meetings and $N$ is the number of rooms.
-   **Space:** $O(N)$ to store room information and counts.

---

## Common Pitfall: End Time Overflow
Since meeting durations can be large ($10^5$) and `end` times can also be large, the delayed `endTime` could exceed $2^{31}-1$. Use `long` for end times.
