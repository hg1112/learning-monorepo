# Java Concurrency

Reference for multi-threaded programming — primitives, patterns, pitfalls, and
interview-critical concurrency scenarios.

---

## Memory Model Fundamentals

### Happens-Before Relationship

The JMM defines when one thread's writes are guaranteed to be visible to another thread.
Happens-before guarantees:
- Actions in a thread happen-before all actions that come after them in program order
- A `synchronized` unlock happens-before any subsequent lock of the same monitor
- A `volatile` write happens-before all subsequent reads of that variable
- `Thread.start()` happens-before any action in the started thread
- Thread termination happens-before `Thread.join()` returns

Without happens-before, the JVM can reorder instructions and cache values in registers.

### Visibility Problem

```java
// Without volatile — BROKEN
boolean running = true;      // main thread writes
// worker thread reads `running` from its CPU cache — may never see false
new Thread(() -> { while (running) { /* spin */ } }).start();
running = false;             // might never propagate to worker thread

// Fix: volatile guarantees write is immediately visible to all threads
volatile boolean running = true;
```

---

## Synchronization Primitives

### 1. `synchronized`

Acquires a monitor lock. Only one thread can hold it at a time.

```java
class Counter {
    private int count = 0;

    public synchronized void increment() { count++; }    // locks on `this`
    public synchronized int get()        { return count; }

    // Lock on a specific object (finer-grained)
    private final Object lock = new Object();
    public void decrement() {
        synchronized (lock) { count--; }
    }
}
```

**Cost:** ~20ns per lock/unlock (uncontested). Under contention: threads block and context-switch (~1-10µs).

### 2. `volatile`

Guarantees visibility (not atomicity). No locking overhead.

```java
volatile boolean shutdown = false;

// Thread 1 sets it
shutdown = true;

// Thread 2 always sees the latest value
while (!shutdown) { doWork(); }
```

**Use when:** One thread writes, other threads only read. If multiple threads write, `volatile` is not enough — use `AtomicBoolean`.

### 3. `ReentrantLock`

Explicit lock with more control than `synchronized`: try-lock, timed-lock, fair ordering.

```java
ReentrantLock lock = new ReentrantLock();

// Basic usage (equivalent to synchronized, but explicit)
lock.lock();
try {
    // critical section
} finally {
    lock.unlock();  // always release in finally
}

// Non-blocking try-lock
if (lock.tryLock(100, TimeUnit.MILLISECONDS)) {
    try { /* ... */ }
    finally { lock.unlock(); }
} else {
    // couldn't acquire lock in time — handle gracefully
}

// Fair lock (FIFO ordering — avoids starvation, but lower throughput)
ReentrantLock fairLock = new ReentrantLock(true);
```

### 4. `ReadWriteLock`

Multiple readers can hold the lock simultaneously; writers get exclusive access.
Best when reads >> writes.

```java
ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
ReadWriteLock.ReadLock  readLock  = rwLock.readLock();
ReadWriteLock.WriteLock writeLock = rwLock.writeLock();

// Driver location cache — many readers, occasional writer
Map<String, LatLng> locationCache = new HashMap<>();

public LatLng getLocation(String driverId) {
    readLock.lock();
    try { return locationCache.get(driverId); }
    finally { readLock.unlock(); }
}

public void updateLocation(String driverId, LatLng loc) {
    writeLock.lock();
    try { locationCache.put(driverId, loc); }
    finally { writeLock.unlock(); }
}
```

**Uber backend:** `ReadWriteLock` (or `ConcurrentHashMap`) for in-memory driver location cache.

### 5. `StampedLock` (Java 8+)

Optimistic read: try to read without locking; validate afterward. Falls back to read lock if validation fails.

```java
StampedLock sl = new StampedLock();

public LatLng getLocation(String driverId) {
    long stamp = sl.tryOptimisticRead();    // no lock acquired
    LatLng loc = cache.get(driverId);
    if (!sl.validate(stamp)) {              // check if a write happened
        stamp = sl.readLock();              // fall back to read lock
        try { loc = cache.get(driverId); }
        finally { sl.unlockRead(stamp); }
    }
    return loc;
}
```

Best for: read-heavy workloads where contention is rare.

---

## Atomic Operations

`java.util.concurrent.atomic` — lock-free thread safety using CAS (Compare-And-Swap).

```java
AtomicInteger counter = new AtomicInteger(0);
counter.incrementAndGet();          // atomic ++
counter.getAndAdd(5);               // atomic += 5
counter.compareAndSet(10, 20);      // if value==10, set to 20; return true/false

AtomicLong rideCounter = new AtomicLong();
AtomicBoolean available = new AtomicBoolean(true);

AtomicReference<DriverState> state = new AtomicReference<>(DriverState.AVAILABLE);
state.compareAndSet(DriverState.AVAILABLE, DriverState.MATCHED);  // atomic state transition
```

**CAS under the hood:**
```
loop:
  expected = current value
  if (memory[addr] == expected)
    memory[addr] = newValue; return true
  else
    return false   // try again
```

No lock needed — CPU instruction (`CMPXCHG`) is atomic.

**LongAdder** (Java 8) — better than `AtomicLong` for high-contention counters:
```java
LongAdder hitCounter = new LongAdder();
hitCounter.increment();             // no CAS spin — each thread has its own cell
long total = hitCounter.sum();      // sum all cells
```

---

## Concurrent Collections

| Need | Use | Why not |
|------|-----|---------|
| Thread-safe key-value | `ConcurrentHashMap` | `Collections.synchronizedMap` (full lock) |
| Thread-safe sorted map | `ConcurrentSkipListMap` | `TreeMap` (not thread-safe) |
| Thread-safe list | `CopyOnWriteArrayList` | `Collections.synchronizedList` |
| Thread-safe queue | `LinkedBlockingQueue` / `ArrayBlockingQueue` | `LinkedList` (not thread-safe) |
| Thread-safe deque | `ConcurrentLinkedDeque` | `ArrayDeque` |

### ConcurrentHashMap

```java
ConcurrentHashMap<String, Integer> map = new ConcurrentHashMap<>();
map.put("a", 1);               // O(1) — locks only the bucket, not the whole map
map.get("a");                  // O(1) — no lock needed (uses volatile reads)
map.putIfAbsent("b", 2);       // atomic
map.compute("a", (k, v) -> v == null ? 1 : v + 1);  // atomic increment
map.merge("a", 1, Integer::sum);  // atomic merge
```

**Internal:** 16 segments by default (Java 7), lock-free reads via `volatile` in Java 8+.
Write locks at bucket level → 16x more concurrency than `Hashtable`.

### BlockingQueue

```java
BlockingQueue<RideRequest> queue = new LinkedBlockingQueue<>(1000);  // bounded

// Producer — blocks if queue full
queue.put(rideRequest);         // blocks
queue.offer(req, 1, SECONDS);  // blocks for up to 1s, returns false if full

// Consumer — blocks if queue empty
RideRequest req = queue.take();              // blocks until available
RideRequest req = queue.poll(1, SECONDS);   // blocks up to 1s, returns null if empty
```

**Uber backend:** Worker thread pools use `LinkedBlockingQueue` as the work queue.
Kafka consumer groups are the distributed version of BlockingQueue.

---

## Thread Pools

### ExecutorService

Never create raw threads (`new Thread()`). Use an executor to manage thread lifecycle.

```java
// Fixed pool — N threads, unbounded queue
ExecutorService pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

// Cached pool — grows as needed, idle threads expire after 60s
ExecutorService cache = Executors.newCachedThreadPool();

// Scheduled pool — for periodic tasks
ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
scheduler.scheduleAtFixedRate(() -> updateSurge(), 0, 30, SECONDS);

// Submit task and get a Future
Future<Driver> future = pool.submit(() -> matchingService.findDriver(rider));
Driver driver = future.get(2, SECONDS);  // blocks up to 2s

// Submit with CompletableFuture (non-blocking)
CompletableFuture<Driver> cf = CompletableFuture.supplyAsync(
    () -> matchingService.findDriver(rider), pool);
cf.thenAccept(driver -> notifyRider(driver));

// Shutdown gracefully
pool.shutdown();
pool.awaitTermination(30, SECONDS);
```

### ThreadPoolExecutor (Custom)

```java
ThreadPoolExecutor executor = new ThreadPoolExecutor(
    4,            // corePoolSize — always running
    16,           // maximumPoolSize — max during burst
    60L,          // keepAliveTime
    TimeUnit.SECONDS,
    new ArrayBlockingQueue<>(1000),          // bounded work queue
    new ThreadPoolExecutor.CallerRunsPolicy() // rejection: caller thread does the work
);
```

**Rejection policies:**
- `AbortPolicy` (default) — throws `RejectedExecutionException`
- `CallerRunsPolicy` — caller thread executes the task (back-pressure)
- `DiscardPolicy` — silently drop the task
- `DiscardOldestPolicy` — drop the oldest queued task, retry

**Uber backend:** Location update processor pool. HTTP request handler pool.
gRPC server uses a `ThreadPoolExecutor` (configurable via `grpc.executor` in Spring gRPC).

---

## Wait / Notify

Low-level signaling. Prefer higher-level constructs (`BlockingQueue`, `CountDownLatch`).

```java
synchronized (monitor) {
    while (!condition) {
        monitor.wait();         // releases lock, waits for notify
    }
    // condition is now true
}

// Another thread signals
synchronized (monitor) {
    condition = true;
    monitor.notifyAll();        // wake all waiting threads
}
```

**Why `while` not `if`:** Spurious wakeups — `wait()` can return without `notify()` being called.
Always re-check condition in a `while` loop.

---

## Higher-Level Concurrency Utilities

### CountDownLatch

Wait for N events to complete.

```java
CountDownLatch latch = new CountDownLatch(3);

// Worker threads
executorService.submit(() -> { doWork(); latch.countDown(); });
executorService.submit(() -> { doWork(); latch.countDown(); });
executorService.submit(() -> { doWork(); latch.countDown(); });

// Main thread waits
latch.await(5, SECONDS);   // blocks until count reaches 0 or timeout
```

**Use case:** Initialize all services before accepting traffic (parallel startup).

### CyclicBarrier

All threads wait at a barrier point. Once all arrive, they all proceed (reusable).

```java
CyclicBarrier barrier = new CyclicBarrier(3, () -> System.out.println("Batch complete"));

// Each of 3 threads processes a chunk, then waits for the others
barrier.await();  // each thread blocks here until all 3 have arrived
```

**Use case:** Parallel batch processing where all workers must finish before the next batch starts.

### Semaphore

Limit concurrent access to a resource (permits).

```java
Semaphore permits = new Semaphore(10);  // max 10 concurrent DB connections

permits.acquire();           // blocks if no permits available
try {
    db.query(sql);
} finally {
    permits.release();       // always release
}

// Non-blocking
if (permits.tryAcquire()) {
    try { db.query(sql); }
    finally { permits.release(); }
} else {
    return cachedResult;
}
```

**Uber backend:** Rate limiting active DB connections in the ride service. Also used to
implement bulkhead pattern — cap concurrent external API calls (payment provider).

---

## Common Concurrency Patterns

### 1. Producer-Consumer

```java
BlockingQueue<RideRequest> queue = new LinkedBlockingQueue<>(500);

// Producers (API threads) — put rides into queue
class RideApiHandler {
    public void handleRequest(RideRequest req) {
        if (!queue.offer(req, 100, MILLISECONDS)) {
            throw new ServiceUnavailableException("Queue full");
        }
    }
}

// Consumers (worker threads) — process rides from queue
class RideProcessor implements Runnable {
    public void run() {
        while (!Thread.currentThread().isInterrupted()) {
            RideRequest req = queue.take();
            matchingService.process(req);
        }
    }
}
```

**Uber backend:** HTTP API threads are producers. Matching engine threads are consumers.
At scale, Kafka replaces the in-process `BlockingQueue`.

### 2. Double-Checked Locking (Driver Lock for Anti-Double-Booking)

```java
// Assign driver atomically — only one rider gets the driver
ConcurrentHashMap<String, String> driverLocks = new ConcurrentHashMap<>();

public boolean tryAssignDriver(String driverId, String riderId) {
    // putIfAbsent is atomic — only first caller wins
    String existing = driverLocks.putIfAbsent(driverId, riderId);
    return existing == null;  // true = this caller won the assignment
}

public void releaseDriver(String driverId) {
    driverLocks.remove(driverId);
}
```

**Uber backend:** In-process driver assignment lock. At scale, replaced by Redis SETNX
or Temporal workflow (see `docs/system_design/uber/rides.md` — anti-double-booking section).

### 3. Immutable Objects (Thread-Safe by Design)

```java
public final class LatLng {             // final — can't be subclassed
    private final double lat;           // final — set once in constructor
    private final double lng;

    public LatLng(double lat, double lng) { this.lat = lat; this.lng = lng; }
    public double getLat() { return lat; }
    public double getLng() { return lng; }
    // no setters
}
```

Immutable objects need no synchronization — share freely between threads.
**Uber backend:** `LatLng`, `RideId`, `DriverId` value objects are all immutable.

### 4. ThreadLocal

Each thread gets its own independent copy of the variable.

```java
ThreadLocal<String> requestId = new ThreadLocal<>();

// In request handler (each request runs on its own thread)
requestId.set(UUID.randomUUID().toString());

// Anywhere in the call stack on the same thread
String id = requestId.get();   // no need to pass as parameter

// CRITICAL: always clean up to avoid memory leaks in thread pools
requestId.remove();
```

**Uber backend:** Request tracing ID propagation through the call stack without passing
it as a parameter to every method. Spring uses `ThreadLocal` for transaction context.

---

## Classic Problems

### Deadlock

Two threads, each holding a lock the other needs.

```java
// Thread A: lock1 → lock2
// Thread B: lock2 → lock1  ← DEADLOCK

// Fix 1: always acquire locks in the same order
// Thread A and B: always lock1 first, then lock2

// Fix 2: use tryLock with timeout
if (lock1.tryLock(100, MILLISECONDS)) {
    try {
        if (lock2.tryLock(100, MILLISECONDS)) {
            try { /* critical section */ }
            finally { lock2.unlock(); }
        }
    } finally { lock1.unlock(); }
}
```

### Race Condition

```java
// BROKEN — check-then-act is not atomic
if (!driverLocks.containsKey(driverId)) {   // Thread A checks: not present
    // Thread B also checks: not present, also enters
    driverLocks.put(driverId, riderId);      // Both assign the driver!
}

// FIXED — putIfAbsent is atomic
String winner = driverLocks.putIfAbsent(driverId, riderId);
boolean won = (winner == null);
```

### ABA Problem (with CAS)

```java
// CAS sees value = A, thinks "hasn't changed," but it went A → B → A
// Fix: use AtomicStampedReference which also compares a version/stamp

AtomicStampedReference<Integer> ref = new AtomicStampedReference<>(100, 0);
int[] stampHolder = new int[1];
int current = ref.get(stampHolder);
int stamp = stampHolder[0];
ref.compareAndSet(current, newValue, stamp, stamp + 1);  // stamp must also match
```

---

## Complexity & Performance Reference

| Operation | Latency | Notes |
|-----------|---------|-------|
| Uncontested `synchronized` | ~5-20 ns | Biased locking (JVM optimization) |
| Contested `synchronized` | ~1-10 µs | Thread context switch |
| `volatile` read | ~5 ns | Memory barrier |
| `AtomicInteger.incrementAndGet` | ~10 ns | CAS loop, usually 1 iteration |
| `ConcurrentHashMap.put` | ~50 ns | Bucket-level lock |
| `LinkedBlockingQueue.put` | ~100 ns | `ReentrantLock` + signal |
| Thread creation (`new Thread`) | ~5 µs | Avoid in hot paths |
| Thread context switch | ~1-10 µs | Kernel call |

---

## Concurrency in Uber Mini-Backend

| Component | Concurrency technique | Why |
|-----------|----------------------|-----|
| Driver lock (anti-double-booking) | `ConcurrentHashMap.putIfAbsent` | Atomic check-and-set without locks |
| Location cache | `ConcurrentHashMap` | High-frequency reads and writes |
| Hit counter | `LongAdder` | High-contention counter, avoids CAS spin |
| gRPC thread pool | `ThreadPoolExecutor` | Handle concurrent streaming connections |
| Kafka consumer | `ThreadPoolExecutor` per partition | Parallel message processing |
| Request tracing | `ThreadLocal` | Pass trace ID without method signature changes |
| Surge calculator | `ReadWriteLock` | Many readers (ride requests), rare writer (recalc) |
| Ride FSM | `AtomicReference<RideState>` | Lock-free state transitions |
| Shutdown hook | `CountDownLatch` | Wait for all in-flight requests to complete |
