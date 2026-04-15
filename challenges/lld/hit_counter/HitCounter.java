package challenges.lld.hit_counter;

import java.util.concurrent.atomic.LongAdder;

/**
 * Design Hit Counter
 *
 * Record per-page visit counts and return hits within any rolling 5-minute window.
 *
 * Pattern cross-reference (docs/lld/):
 *   - Singleton (patterns_tier1.md #4): one HitCounter instance per page, shared across threads
 *   - Concurrency (concurrency.md): LongAdder for high-contention counters (avoids CAS spin),
 *     AtomicLong for the multi-threaded circular-buffer variant
 *
 * Data structure (docs/data_structures/):
 *   - Circular array of size 300 (one bucket per second in the 5-min window).
 *     Amortizes to O(1) hit() and O(300) = O(1) getHits().
 *
 * ── Single-threaded version ──────────────────────────────────────────────────
 *
 * Key insight: We only need to remember the last 300 seconds.
 *   bucket index = timestamp % 300
 *   If the stored timestamp matches → increment; otherwise reset (old data).
 *
 * ── Multi-threaded version ────────────────────────────────────────────────────
 *
 * Two critical races:
 *   1. hit() — concurrent writes to the same bucket (check-then-act)
 *   2. getHits() — reading while concurrent writes are happening
 *
 * Fix: per-bucket synchronization with LongAdder for the count.
 * We synchronize only on the bucket being written, not on the whole array.
 */

// ─── Single-Threaded ─────────────────────────────────────────────────────────

class HitCounterSingle {

    private static final int WINDOW = 300;

    private final int[]  times = new int[WINDOW];   // which second filled this bucket
    private final long[] hits  = new long[WINDOW];  // count for that second

    /** O(1) — record a hit at this timestamp (seconds since epoch). */
    public void hit(int timestamp) {
        int idx = timestamp % WINDOW;
        if (times[idx] == timestamp) {
            hits[idx]++;
        } else {
            times[idx] = timestamp;
            hits[idx]  = 1;
        }
    }

    /** O(300) = O(1) — return total hits in [timestamp-299, timestamp]. */
    public long getHits(int timestamp) {
        long total = 0;
        for (int i = 0; i < WINDOW; i++) {
            // bucket is valid if its timestamp is inside the current window
            if (timestamp - times[i] < WINDOW) {
                total += hits[i];
            }
        }
        return total;
    }
}

// ─── Multi-Threaded ───────────────────────────────────────────────────────────
//
// Problem with the single-threaded version under concurrency:
//   Thread A: times[idx] != timestamp  → decides to reset
//   Thread B: times[idx] != timestamp  → decides to reset
//   Both set hits[idx] = 1 (one hit is lost)
//
// Solution: per-bucket lock objects + LongAdder.
//   - LongAdder (concurrency.md) distributes increments across CPU-local cells
//     to avoid CAS contention, then sums them for reads.
//   - We synchronize on bucket[i] only for the reset path (rare), not for every hit.

class HitCounterMultiThreaded {

    private static final int WINDOW = 300;

    private final int[]      times   = new int[WINDOW];
    private final LongAdder[] counts = new LongAdder[WINDOW];
    private final Object[]   locks   = new Object[WINDOW];

    public HitCounterMultiThreaded() {
        for (int i = 0; i < WINDOW; i++) {
            counts[i] = new LongAdder();
            locks[i]  = new Object();
        }
    }

    /**
     * Thread-safe hit recording.
     *
     * Fast path (timestamp matches): LongAdder.increment() — no lock needed,
     * using striped counter cells per CPU.
     *
     * Slow path (stale bucket): synchronized reset, then increment.
     */
    public void hit(int timestamp) {
        int idx = timestamp % WINDOW;
        if (times[idx] != timestamp) {          // stale — needs reset (rare)
            synchronized (locks[idx]) {
                if (times[idx] != timestamp) {  // double-checked locking
                    times[idx] = timestamp;
                    counts[idx].reset();
                }
            }
        }
        counts[idx].increment();                // lock-free fast path
    }

    /**
     * Returns total hits in the last 300 seconds ending at timestamp.
     * Reads are eventually consistent (LongAdder.sum() is not atomic),
     * which is acceptable for analytics counters.
     */
    public long getHits(int timestamp) {
        long total = 0;
        for (int i = 0; i < WINDOW; i++) {
            if (timestamp - times[i] < WINDOW) {
                total += counts[i].sum();
            }
        }
        return total;
    }
}

// ─── Per-Page Counter Facade ──────────────────────────────────────────────────
//
// Facade pattern (patterns_tier1.md #12): one entry point for all page counting.
// Singleton (patterns_tier1.md #4): one HitCounterService per JVM.

class HitCounterService {

    private static volatile HitCounterService instance;

    // ConcurrentHashMap (concurrency.md) — thread-safe, bucket-level locking
    private final java.util.concurrent.ConcurrentHashMap<String, HitCounterMultiThreaded>
        counters = new java.util.concurrent.ConcurrentHashMap<>();

    private HitCounterService() {}

    public static HitCounterService getInstance() {
        if (instance == null) {
            synchronized (HitCounterService.class) {
                if (instance == null) instance = new HitCounterService();
            }
        }
        return instance;
    }

    public void recordHit(String pageId, int timestamp) {
        counters.computeIfAbsent(pageId, k -> new HitCounterMultiThreaded())
                .hit(timestamp);
    }

    public long getHits(String pageId, int timestamp) {
        HitCounterMultiThreaded counter = counters.get(pageId);
        return counter == null ? 0 : counter.getHits(timestamp);
    }
}
