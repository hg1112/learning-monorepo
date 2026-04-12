# Queues & Stacks

Linear data structures that manage element ordering via FIFO or LIFO rules,
plus their generalizations: deque, priority queue, and monotonic deque.

---

## 1. Stack (LIFO: Last-In, First-Out)

Like a stack of plates — add and remove only from the top.

### Visual

```text
Push(5)  → [ 5 ]
Push(10) → [ 5 | 10 ] ← top
Pop()    → returns 10,  stack: [ 5 ]
```

### Java API — use `Deque`, not `Stack`

`Stack<E>` is a legacy class (extends `Vector`, synchronized). Use `ArrayDeque` instead.

```java
Deque<Integer> stack = new ArrayDeque<>();
stack.push(10);      // addFirst — O(1)
stack.peek();        // peekFirst — O(1), null-safe: peekFirst()
stack.pop();         // removeFirst — O(1)
stack.isEmpty();
```

---

## 2. Queue (FIFO: First-In, First-Out)

Like a checkout line — first in, first served.

### Visual

```text
offer(5)  → [ 5 ]
offer(10) → [ 5 | 10 ] ← tail
poll()    → returns 5,  queue: [ 10 ]
```

### Java API

```java
Queue<Integer> q = new LinkedList<>();   // or ArrayDeque for better performance
q.offer(5);      // enqueue — O(1), returns false if capacity exceeded
q.add(10);       // enqueue — throws if capacity exceeded
q.peek();        // head, null if empty
q.poll();        // dequeue — null if empty
q.remove();      // dequeue — throws if empty
```

**Prefer `ArrayDeque` over `LinkedList`** for queues: better cache locality, no node allocation overhead.

---

## 3. Deque (Double-Ended Queue)

Add/remove from both ends. Used as both stack and queue; also the backbone of the monotonic deque pattern.

### Visual

```text
Front [ 10 | 20 | 30 ] Back
addFirst(5)  → [ 5 | 10 | 20 | 30 ]
addLast(40)  → [ 5 | 10 | 20 | 30 | 40 ]
pollFirst()  → returns 5
pollLast()   → returns 40
```

### Java API

```java
Deque<Integer> dq = new ArrayDeque<>();
dq.addFirst(10);   dq.offerFirst(10);   // O(1)
dq.addLast(20);    dq.offerLast(20);    // O(1)
dq.peekFirst();    dq.peekLast();       // O(1)
dq.pollFirst();    dq.pollLast();       // O(1)
dq.removeFirst();  dq.removeLast();     // throws if empty
```

---

## 4. PriorityQueue (Binary Heap)

Processes elements in priority order. Java's `PriorityQueue<E>` is a **min-heap** by default.

### Binary Heap Internals

Stored as a 1-indexed array. For node at index `i`:
- Left child: `2i`
- Right child: `2i+1`
- Parent: `i/2`

**Heap property:** every parent ≤ both children (min-heap).

**Sift-up (after add):** bubble the new element up until heap property holds.
**Sift-down (after poll):** swap root with last element, remove last, bubble down.

```text
Min-heap as array:   [_, 1, 3, 5, 7, 9, 8, 6]
                          ^                     index 1 = root (min)
Tree view:
         1
        / \
       3   5
      / \ / \
     7  9 8  6
```

### Java API

```java
// Min-heap (default)
PriorityQueue<Integer> minPQ = new PriorityQueue<>();

// Max-heap
PriorityQueue<Integer> maxPQ = new PriorityQueue<>(Collections.reverseOrder());

// Custom comparator — sort by first element, then second
PriorityQueue<int[]> pq = new PriorityQueue<>((a, b) ->
    a[0] != b[0] ? a[0] - b[0] : a[1] - b[1]);

pq.offer(new int[]{10, 2});
pq.peek();    // O(1) — min element
pq.poll();    // O(log N) — remove min, sift-down
pq.add(x);   // O(log N) — add, sift-up
pq.size();    // O(1)
```

### Complexity

| Operation | Time | Note |
|-----------|------|------|
| `peek` | O(1) | Root is always min |
| `offer`/`add` | O(log N) | Sift-up |
| `poll` | O(log N) | Sift-down |
| `remove(obj)` | O(N) | Linear scan + O(log N) sift |
| Build heap from N elements | O(N) | Floyd's heapify |
| `contains` | O(N) | No index on arbitrary elements |

---

## 5. Two Min-Heaps Pattern

Use two priority queues when you need to track **free resources** (by index) and
**in-use resources** (by release time) simultaneously.

### Template

```java
PriorityQueue<Integer> free     = new PriorityQueue<>();               // min by index
PriorityQueue<long[]>  occupied = new PriorityQueue<>((a,b) ->        // min by endTime, then index
    a[0] != b[0] ? Long.compare(a[0],b[0]) : Long.compare(a[1],b[1]));
```

### Worked Example — Meeting Rooms III

**LeetCode 2402** | [Solution.java](../../challenges/leetcode/MeetingsRoomsIII/Solution.java)

**Problem:** n rooms, meetings arrive sorted by start. Each meeting uses the
lowest-indexed free room; if none free, it waits for the earliest-ending room.
Find the room that held the most meetings.

**Key insight:**
- `freeRooms`: min-heap of room indices → always get lowest-index free room
- `occupiedRooms`: min-heap of `[endTime, roomIndex]` → always know which room frees next

```java
public int mostBooked(int n, int[][] meetings) {
    Arrays.sort(meetings, (a, b) -> a[0] - b[0]);  // sort by start time
    int[] count = new int[n];

    PriorityQueue<Integer> free =
        new PriorityQueue<>();
    PriorityQueue<long[]> occupied =
        new PriorityQueue<>((a,b) -> a[0]!=b[0] ? Long.compare(a[0],b[0])
                                                  : Long.compare(a[1],b[1]));
    for (int i = 0; i < n; i++) free.offer(i);

    for (int[] m : meetings) {
        long start = m[0], end = m[1];
        // release rooms that have ended before this meeting starts
        while (!occupied.isEmpty() && occupied.peek()[0] <= start) {
            free.offer((int) occupied.poll()[1]);
        }
        if (!free.isEmpty()) {
            int room = free.poll();
            occupied.offer(new long[]{end, room});
            count[room]++;
        } else {
            // all rooms busy — delay this meeting
            long[] earliest = occupied.poll();
            long newEnd = earliest[0] + (end - start);   // same duration, delayed start
            int room = (int) earliest[1];
            occupied.offer(new long[]{newEnd, room});
            count[room]++;
        }
    }
    // find room with max count (lowest index breaks ties)
    int ans = 0;
    for (int i = 1; i < n; i++)
        if (count[i] > count[ans]) ans = i;
    return ans;
}
```

**Delayed meeting timeline:**
```
[Room 0] |=====M1 ends@10=====|
[Room 1] |=====M2 ends@15=====|

M3: start=5, duration=10
→ All rooms busy at t=5
→ Earliest free: Room 0 @ t=10
→ M3 delayed: newEnd = 10 + 10 = 20

[Room 0] |=M1=| · · · |=====M3 ends@20=====|
```

**Pitfall:** end times can overflow `int`. Use `long` for `newEnd`.

**Complexity:** O(M log M + M log N), Space: O(N)

---

## 6. Monotonic Deque Pattern (Sliding Window Min/Max)

Use two `Deque<Integer>` (storing indices) to track the minimum and maximum of a
sliding window in O(1) amortized per element.

### Key Rules

- **maxDeque** (decreasing): when adding index `r`, pop from back while `nums[back] ≤ nums[r]`
- **minDeque** (increasing): when adding index `r`, pop from back while `nums[back] ≥ nums[r]`
- Pop from front when the front index has slid out of the window (`front < left`)

### Template

```java
Deque<Integer> maxDq = new ArrayDeque<>();  // decreasing — front is max
Deque<Integer> minDq = new ArrayDeque<>();  // increasing — front is min

int left = 0, ans = 0;
for (int right = 0; right < nums.length; right++) {
    // maintain decreasing deque
    while (!maxDq.isEmpty() && nums[maxDq.peekLast()] <= nums[right])
        maxDq.pollLast();
    maxDq.addLast(right);

    // maintain increasing deque
    while (!minDq.isEmpty() && nums[minDq.peekLast()] >= nums[right])
        minDq.pollLast();
    minDq.addLast(right);

    // shrink window until condition holds
    while (nums[maxDq.peekFirst()] - nums[minDq.peekFirst()] > limit) {
        left++;
        if (maxDq.peekFirst() < left) maxDq.pollFirst();
        if (minDq.peekFirst() < left) minDq.pollFirst();
    }
    ans = Math.max(ans, right - left + 1);
}
```

### Worked Example — Longest Subarray With Abs Diff ≤ Limit

**LeetCode 1438** | [Solution.java](../../challenges/leetcode/LongestContiguousSubArrayWithAbsDiffLessThanLimit/Solution.java)

**Problem:** Find the longest subarray where `max(window) − min(window) ≤ limit`.

**Trace: nums = [8, 2, 4, 7], limit = 4**

```
right=0 (val:8)  maxDq:[0]    minDq:[0]    window:[8]       diff=0 ✓  len=1
right=1 (val:2)  maxDq:[0,1]  minDq:[1]    window:[8,2]     diff=6 ✗
  shrink: left=1, pop 0 from maxDq → maxDq:[1]  window:[2]   diff=0 ✓  len=1
right=2 (val:4)  maxDq:[2]    minDq:[1,2]  window:[2,4]     diff=2 ✓  len=2
right=3 (val:7)  maxDq:[3]    minDq:[1,2,3] window:[2,4,7]  diff=5 ✗
  shrink: left=2, pop 1 from minDq → minDq:[2,3] window:[4,7] diff=3 ✓ len=2
```

**Why deque beats TreeMap here:**

| Approach | Time | Space | Note |
|----------|------|-------|------|
| Binary Search + TreeMap | O(N log² N) | O(N) | Two log factors |
| Sliding Window + TreeMap | O(N log N) | O(N) | One log factor |
| Sliding Window + Deques | **O(N)** | O(N) | Optimal |

Each element is added/removed at most once from each deque → amortized O(1).

---

## Performance Summary

| Structure | push/offer | pop/poll | peek | Search |
|-----------|-----------|---------|------|--------|
| Stack (ArrayDeque) | O(1) | O(1) | O(1) | O(N) |
| Queue (ArrayDeque) | O(1) | O(1) | O(1) | O(N) |
| Deque | O(1) | O(1) | O(1) | O(N) |
| PriorityQueue | O(log N) | O(log N) | O(1) | O(N) |
| Monotonic Deque | O(1) amortized | O(1) | O(1) | — |

---

## When to Use Which

| Situation | Structure |
|-----------|-----------|
| Undo/redo, DFS frontier | Stack |
| BFS frontier, producer-consumer | Queue |
| Sliding window min/max in O(N) | Monotonic Deque |
| K-th largest/smallest, always need min or max | PriorityQueue |
| Track free + occupied resources simultaneously | Two PriorityQueues |
| Need both ends (palindrome check, deque-BFS) | Deque |
