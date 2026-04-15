package challenges.lld.train_platform;

import java.util.*;

/**
 * Design a Train Platform Management System
 *
 * Assign trains to platforms; one train per platform at any time.
 * Support time-based queries: "which trains were at which platforms at time T?"
 *
 * Pattern cross-reference (docs/lld/):
 *   - State (patterns_tier1.md #7): Train FSM — SCHEDULED → ARRIVED → DEPARTED.
 *     Platform transitions: EMPTY ↔ OCCUPIED.
 *   - Strategy (patterns_tier1.md #1): PlatformAssignmentStrategy — pluggable policy
 *     (e.g. nearest-available, prefer-platform-type).
 *   - Builder (patterns_tier1.md #3): TrainArrival.Builder for complex arrival objects.
 *
 * Data structure (docs/data_structures/treemap_treeset.md):
 *   - Per platform: TreeMap<arrivalTime, TrainArrival> for time-based queries.
 *     lowerEntry(t) + ceilingEntry(t) answer "what was here at time T" in O(log n).
 *
 * Key methods implemented:
 *   1. assignPlatform()  — find a free platform and record the assignment
 *   2. depart()          — mark a train as departed, freeing the platform
 *   3. queryAtTime()     — for a given timestamp, return platform → trainId mapping
 */

// ─── Domain ───────────────────────────────────────────────────────────────────

enum TrainState { SCHEDULED, ARRIVED, DEPARTED }

class Train {
    final String     trainId;
    TrainState       state = TrainState.SCHEDULED;
    String           currentPlatformId;

    Train(String trainId) { this.trainId = trainId; }
}

class TrainArrival {
    final String trainId;
    final int    arrivalTime;   // minutes since midnight
    int          departureTime; // set on depart()

    TrainArrival(String trainId, int arrivalTime) {
        this.trainId     = trainId;
        this.arrivalTime = arrivalTime;
        this.departureTime = Integer.MAX_VALUE;   // sentinel: still present
    }

    boolean isPresentAt(int time) {
        return arrivalTime <= time && time < departureTime;
    }
}

// ─── Platform ─────────────────────────────────────────────────────────────────

class Platform {
    final String platformId;

    // History: arrivalTime → TrainArrival (sorted by arrival time)
    private final TreeMap<Integer, TrainArrival> history = new TreeMap<>();
    private TrainArrival current = null;   // null when empty

    Platform(String platformId) { this.platformId = platformId; }

    boolean isAvailableAt(int time) {
        return queryAt(time) == null;
    }

    boolean isCurrentlyAvailable() {
        return current == null;
    }

    void recordArrival(String trainId, int arrivalTime) {
        if (!isCurrentlyAvailable())
            throw new IllegalStateException("Platform " + platformId + " is occupied");
        current = new TrainArrival(trainId, arrivalTime);
        history.put(arrivalTime, current);
    }

    void recordDeparture(String trainId, int departureTime) {
        if (current == null || !current.trainId.equals(trainId))
            throw new IllegalStateException(
                "Train " + trainId + " is not on platform " + platformId);
        current.departureTime = departureTime;
        current = null;
    }

    /**
     * What train was on this platform at a given time?
     * O(log n) — find the last arrival at or before `time`, check if still present.
     */
    String queryAt(int time) {
        Map.Entry<Integer, TrainArrival> entry = history.floorEntry(time);
        if (entry == null) return null;
        TrainArrival arrival = entry.getValue();
        return arrival.isPresentAt(time) ? arrival.trainId : null;
    }

    String getCurrentTrain() {
        return current == null ? null : current.trainId;
    }
}

// ─── Assignment Strategy (Strategy pattern) ───────────────────────────────────

interface PlatformAssignmentStrategy {
    Optional<Platform> assign(Map<String, Platform> platforms);
}

/** Simplest policy: first available platform in iteration order. */
class FirstAvailableStrategy implements PlatformAssignmentStrategy {
    public Optional<Platform> assign(Map<String, Platform> platforms) {
        return platforms.values().stream()
            .filter(Platform::isCurrentlyAvailable)
            .findFirst();
    }
}

/** Prefer the platform with the lowest ID (consistent, deterministic). */
class LowestIdStrategy implements PlatformAssignmentStrategy {
    public Optional<Platform> assign(Map<String, Platform> platforms) {
        return platforms.entrySet().stream()
            .filter(e -> e.getValue().isCurrentlyAvailable())
            .min(Map.Entry.comparingByKey())
            .map(Map.Entry::getValue);
    }
}

// ─── Train Platform Service ───────────────────────────────────────────────────

public class TrainPlatformSystem {

    private final Map<String, Platform> platforms = new LinkedHashMap<>();
    private final Map<String, Train>    trains    = new HashMap<>();
    private PlatformAssignmentStrategy  strategy  = new LowestIdStrategy();

    public TrainPlatformSystem(List<String> platformIds) {
        for (String id : platformIds) platforms.put(id, new Platform(id));
    }

    public void setStrategy(PlatformAssignmentStrategy strategy) {
        this.strategy = strategy;
    }

    // ── 1. assignPlatform ─────────────────────────────────────────────────────
    /**
     * Assigns a free platform to a train arriving at the given time.
     * Delegates platform selection to the pluggable Strategy.
     *
     * O(P) for the strategy scan + O(log n) for history insertion.
     */
    public String assignPlatform(String trainId, int arrivalTime) {
        if (trains.containsKey(trainId)) {
            Train t = trains.get(trainId);
            if (t.state == TrainState.ARRIVED)
                throw new IllegalStateException("Train already on platform: " + trainId);
        }

        Platform platform = strategy.assign(platforms)
            .orElseThrow(() -> new IllegalStateException("No platforms available"));

        Train train = trains.computeIfAbsent(trainId, Train::new);
        train.state             = TrainState.ARRIVED;
        train.currentPlatformId = platform.platformId;

        platform.recordArrival(trainId, arrivalTime);
        return platform.platformId;
    }

    // ── 2. depart ─────────────────────────────────────────────────────────────
    /**
     * Marks the train as departed at the given time, freeing the platform.
     * O(1) + O(log n) history update.
     */
    public void depart(String trainId, int departureTime) {
        Train train = trains.get(trainId);
        if (train == null || train.state != TrainState.ARRIVED)
            throw new IllegalStateException("Train not currently on a platform: " + trainId);

        Platform platform = platforms.get(train.currentPlatformId);
        platform.recordDeparture(trainId, departureTime);

        train.state             = TrainState.DEPARTED;
        train.currentPlatformId = null;
    }

    // ── 3. queryAtTime ────────────────────────────────────────────────────────
    /**
     * Returns a snapshot of which train was on each platform at the given time.
     * Absent entries mean the platform was empty.
     *
     * O(P log n) — one floorEntry() per platform.
     */
    public Map<String, String> queryAtTime(int time) {
        Map<String, String> snapshot = new LinkedHashMap<>();
        for (Platform p : platforms.values()) {
            String trainId = p.queryAt(time);
            if (trainId != null) snapshot.put(p.platformId, trainId);
        }
        return snapshot;
    }

    public Map<String, String> currentStatus() {
        Map<String, String> status = new LinkedHashMap<>();
        for (Platform p : platforms.values()) {
            String t = p.getCurrentTrain();
            status.put(p.platformId, t == null ? "<empty>" : t);
        }
        return status;
    }

    // ── Demo ──────────────────────────────────────────────────────────────────
    public static void main(String[] args) {
        TrainPlatformSystem sys = new TrainPlatformSystem(
            Arrays.asList("P1", "P2", "P3"));

        sys.assignPlatform("T100", 600);   // 10:00 — T100 → P1
        sys.assignPlatform("T200", 615);   // 10:15 — T200 → P2
        sys.depart("T100", 630);           // 10:30 — T100 departs P1

        sys.assignPlatform("T300", 645);   // 10:45 — T300 → P1 (now free)

        System.out.println("At 10:00: " + sys.queryAtTime(600));   // {P1=T100}
        System.out.println("At 10:20: " + sys.queryAtTime(620));   // {P1=T100, P2=T200}
        System.out.println("At 10:40: " + sys.queryAtTime(640));   // {P2=T200}
        System.out.println("At 11:00: " + sys.queryAtTime(660));   // {P1=T300, P2=T200}
        System.out.println("Current:  " + sys.currentStatus());
    }
}
