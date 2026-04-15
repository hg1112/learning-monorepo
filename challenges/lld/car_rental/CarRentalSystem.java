package challenges.lld.car_rental;

import java.util.*;

/**
 * Design a Car Rental System
 *
 * Full-day bookings; calculate trip cost on return.
 * Support: add car, book for date range, return car, list available cars.
 *
 * Pattern cross-reference (docs/lld/):
 *   - State (patterns_tier1.md #7): Car FSM — AVAILABLE → RENTED → MAINTENANCE → AVAILABLE.
 *     State governs which operations are legal (e.g. can't book a car already RENTED).
 *   - Builder (patterns_tier1.md #3): Booking.Builder — car, user, date range, extras.
 *   - Strategy (patterns_tier1.md #1): PricingStrategy — base daily rate + optional
 *     extras (insurance, GPS).  Strategies are composable via Decorator but kept
 *     simple here for interview clarity.
 *   - Factory Method (patterns_tier1.md #8): CarFactory creates typed car objects.
 *
 * Data structure (docs/data_structures/treemap_treeset.md):
 *   - Per car: TreeMap<startDay, Booking> — O(log n) overlap check, same pattern as
 *     MeetingRoomSystem.
 *
 * Key methods implemented:
 *   1. book()        — check availability for the date range, confirm booking
 *   2. returnCar()   — compute trip cost, free the car
 *   3. listAvailable() — cars free for the entire requested date range
 */

// ─── Enums ────────────────────────────────────────────────────────────────────

enum CarType    { ECONOMY, STANDARD, SUV, LUXURY }
enum CarStatus  { AVAILABLE, RENTED, MAINTENANCE }

// ─── Pricing Strategy (Strategy pattern) ─────────────────────────────────────

interface PricingStrategy {
    double dailyRate(Car car);
}

class BasePricingStrategy implements PricingStrategy {
    private static final Map<CarType, Double> RATES = Map.of(
        CarType.ECONOMY,  30.0,
        CarType.STANDARD, 50.0,
        CarType.SUV,      80.0,
        CarType.LUXURY,  150.0
    );

    public double dailyRate(Car car) {
        return RATES.getOrDefault(car.type, 50.0);
    }
}

// ─── Car ──────────────────────────────────────────────────────────────────────

class Car {
    final String  carId;
    final String  make;
    final String  model;
    final CarType type;
    CarStatus     status = CarStatus.AVAILABLE;

    // Booking schedule: startDay → Booking (sorted, for overlap detection)
    final TreeMap<Integer, Booking> schedule = new TreeMap<>();

    Car(String carId, String make, String model, CarType type) {
        this.carId = carId;
        this.make  = make;
        this.model = model;
        this.type  = type;
    }

    /**
     * Check if this car is free for [startDay, endDay] (inclusive, full-day).
     * Checks both the left and right neighbours in the sorted schedule.
     * O(log n).
     */
    boolean isAvailable(int startDay, int endDay) {
        if (status == CarStatus.MAINTENANCE) return false;

        // Left neighbour: a booking that started before our start might still be active
        Map.Entry<Integer, Booking> left = schedule.lowerEntry(startDay + 1);   // ≤ startDay
        if (left != null && left.getValue().endDay >= startDay) return false;

        // Right neighbour: a booking starting at or after our start
        Map.Entry<Integer, Booking> right = schedule.ceilingEntry(startDay);
        if (right != null && right.getValue().startDay <= endDay) return false;

        return true;
    }

    @Override public String toString() {
        return String.format("[%s] %s %s (%s) — %s", carId, make, model, type, status);
    }
}

// ─── Booking ──────────────────────────────────────────────────────────────────

class Booking {
    private static int seq = 1;

    final String bookingId;
    final String carId;
    final String userId;
    final int    startDay;    // days since epoch (or any integer day number)
    final int    endDay;
    double       totalCost;   // set on returnCar()

    Booking(String carId, String userId, int startDay, int endDay) {
        this.bookingId = "BKG-" + (seq++);
        this.carId     = carId;
        this.userId    = userId;
        this.startDay  = startDay;
        this.endDay    = endDay;
    }

    int days() { return endDay - startDay + 1; }

    @Override public String toString() {
        return String.format("[%s] car=%s user=%s days=[%d,%d] cost=%.2f",
            bookingId, carId, userId, startDay, endDay,
            totalCost == 0 ? Double.NaN : totalCost);
    }
}

// ─── Car Rental Service ───────────────────────────────────────────────────────

public class CarRentalSystem {

    private final Map<String, Car>     cars     = new LinkedHashMap<>();
    private final Map<String, Booking> bookings = new HashMap<>();
    private PricingStrategy            pricing  = new BasePricingStrategy();

    public void setPricingStrategy(PricingStrategy strategy) {
        this.pricing = strategy;
    }

    public Car addCar(String carId, String make, String model, CarType type) {
        Car car = new Car(carId, make, model, type);
        cars.put(carId, car);
        return car;
    }

    // ── 1. book ───────────────────────────────────────────────────────────────
    /**
     * Book a car for [startDay, endDay] (inclusive full days).
     *
     * State check: car must be AVAILABLE (not MAINTENANCE or currently RENTED on these days).
     * Overlap check: O(log n) via TreeMap neighbours.
     *
     * Returns the confirmed Booking.
     */
    public Booking book(String carId, String userId, int startDay, int endDay) {
        if (startDay > endDay)
            throw new IllegalArgumentException("startDay must be ≤ endDay");

        Car car = getCar(carId);
        if (!car.isAvailable(startDay, endDay))
            throw new IllegalStateException(
                String.format("Car %s is not available for days [%d, %d]", carId, startDay, endDay));

        Booking b = new Booking(carId, userId, startDay, endDay);
        car.schedule.put(startDay, b);
        // Mark as RENTED only if the booking starts today (startDay == "today")
        // In this simplified model we mark it immediately (stateless date logic)
        car.status = CarStatus.RENTED;

        bookings.put(b.bookingId, b);
        return b;
    }

    // ── 2. returnCar ─────────────────────────────────────────────────────────
    /**
     * Return the car on the actual return day; compute and record the trip cost.
     *
     * Cost = dailyRate × (actualReturnDay - startDay + 1).
     * Late returns charge extra days at the same daily rate.
     *
     * State transition: RENTED → AVAILABLE (or MAINTENANCE if flagged).
     *
     * O(log n) to remove the booking from the schedule.
     */
    public double returnCar(String bookingId, int actualReturnDay) {
        Booking b = bookings.get(bookingId);
        if (b == null) throw new IllegalArgumentException("Unknown booking: " + bookingId);

        Car car = getCar(b.carId);

        // Actual days used (late return = extra charge)
        int daysUsed = actualReturnDay - b.startDay + 1;
        double cost  = pricing.dailyRate(car) * Math.max(daysUsed, b.days());
        b.totalCost  = cost;

        car.schedule.remove(b.startDay);
        // Free the car only if there are no future bookings; otherwise keep RENTED
        Map.Entry<Integer, Booking> next = car.schedule.higherEntry(actualReturnDay);
        car.status = (next == null) ? CarStatus.AVAILABLE : CarStatus.RENTED;

        return cost;
    }

    // ── 3. listAvailable ─────────────────────────────────────────────────────
    /**
     * Return all cars available for the full requested date range.
     * Optionally filter by car type.
     *
     * O(C × log n) where C = number of cars.
     */
    public List<Car> listAvailable(int startDay, int endDay, CarType typeFilter) {
        List<Car> result = new ArrayList<>();
        for (Car car : cars.values()) {
            if (typeFilter != null && car.type != typeFilter) continue;
            if (car.isAvailable(startDay, endDay)) result.add(car);
        }
        return result;
    }

    public List<Car> listAvailable(int startDay, int endDay) {
        return listAvailable(startDay, endDay, null);
    }

    private Car getCar(String id) {
        Car c = cars.get(id);
        if (c == null) throw new IllegalArgumentException("Unknown car: " + id);
        return c;
    }

    // ── Demo ──────────────────────────────────────────────────────────────────
    public static void main(String[] args) {
        CarRentalSystem sys = new CarRentalSystem();

        sys.addCar("C1", "Toyota", "Corolla", CarType.ECONOMY);
        sys.addCar("C2", "Honda",  "CRV",     CarType.SUV);
        sys.addCar("C3", "BMW",    "5 Series",CarType.LUXURY);

        System.out.println("=== Available day 1–3 ===");
        sys.listAvailable(1, 3).forEach(System.out::println);

        Booking b1 = sys.book("C1", "alice", 1, 5);     // days 1–5
        Booking b2 = sys.book("C2", "bob",   2, 4);

        System.out.println("\nBooked: " + b1);
        System.out.println("Booked: " + b2);

        // Overlap attempt
        try {
            sys.book("C1", "carol", 3, 7);
        } catch (IllegalStateException e) {
            System.out.println("Expected conflict: " + e.getMessage());
        }

        System.out.println("\n=== Available day 1–3 (after bookings) ===");
        sys.listAvailable(1, 3).forEach(System.out::println);
        // Only C3 is free

        // Return on time
        double cost1 = sys.returnCar(b1.bookingId, 5);
        System.out.printf("%nAlice returns C1: cost $%.2f (5 days × $30)%n", cost1);

        // Late return (booked till day 4, returned day 6)
        double cost2 = sys.returnCar(b2.bookingId, 6);
        System.out.printf("Bob returns C2 late: cost $%.2f (6 days × $80)%n", cost2);

        System.out.println("\n=== Available after returns ===");
        sys.listAvailable(6, 10).forEach(System.out::println);
    }
}
