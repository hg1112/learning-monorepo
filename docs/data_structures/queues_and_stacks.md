# Queues & Stacks

Linear data structures that manage the order of elements based on specific rules (FIFO/LIFO).

---

## 1. Stack (LIFO: Last-In, First-Out)
Like a stack of plates. You can only add or remove from the top.

### Visual Representation
```text
Push (5) -> [ 5 ]
Push (10) -> [ 5, 10 ] <-- Top
Pop () -> Returns 10, Stack becomes [ 5 ]
```

### Java API (Use Deque instead of Stack)
In Java, `Stack` is legacy. Use `Deque` for better performance.
```java
Deque<Integer> stack = new ArrayDeque<>();
stack.push(10);     // Add to top
stack.peek();       // Look at top
stack.pop();        // Remove from top
```

---

## 2. Queue (FIFO: First-In, First-Out)
Like a line at a grocery store. The first person in is the first person out.

### Visual Representation
```text
Enqueue (5) -> [ 5 ]
Enqueue (10) -> [ 5, 10 ] <-- Tail
Dequeue () -> Returns 5, Queue becomes [ 10 ]
```

### Java API
```java
Queue<Integer> queue = new LinkedList<>();
queue.offer(5);     // Enqueue (Safe, returns false if full)
queue.add(10);      // Enqueue (Throws exception if full)
queue.peek();       // Look at head
queue.poll();       // Dequeue (Returns null if empty)
queue.remove();     // Dequeue (Throws exception if empty)
```

---

## 3. Deque (Double-Ended Queue)
A queue where you can add or remove from both ends.

### Visual Representation
```text
Front [ 10, 20, 30 ] Back
Add (5, Front) -> [ 5, 10, 20, 30 ]
Add (40, Back) -> [ 5, 10, 20, 30, 40 ]
```

### Java API (ArrayDeque is preferred)
```java
Deque<Integer> deque = new ArrayDeque<>();
deque.addFirst(10);
deque.addLast(20);
deque.pollFirst();
deque.pollLast();
```

---

## 4. PriorityQueue (Heaps)
A specialized queue where elements are ordered by priority (natural order or custom comparator).

### Mechanics
- Internally implemented as a **Binary Heap**.
- `peek()`: $O(1)$.
- `add()` / `poll()`: $O(\log N)$.

### Java API
```java
// Min-heap (Default)
PriorityQueue<Integer> minHeap = new PriorityQueue<>();

// Max-heap
PriorityQueue<Integer> maxHeap = new PriorityQueue<>(Collections.reverseOrder());

// Custom Priority (e.g., sort by string length)
PriorityQueue<String> pq = new PriorityQueue<>((a, b) -> a.length() - b.length());
```

---

## Performance Summary

| Operation | Stack (LIFO) | Queue (FIFO) | PriorityQueue |
| :--- | :--- | :--- | :--- |
| **Push/Offer** | $O(1)$ | $O(1)$ | $O(\log N)$ |
| **Pop/Poll** | $O(1)$ | $O(1)$ | $O(\log N)$ |
| **Peek** | $O(1)$ | $O(1)$ | $O(1)$ |
| **Search** | $O(N)$ | $O(N)$ | $O(N)$ |
