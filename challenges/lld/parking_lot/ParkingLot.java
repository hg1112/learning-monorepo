package challenges.lld.parking_lot;

import java.util.*;

/**
 * Design a Parking Lot (multi-floor, 2-wheeler and 4-wheeler)
 *
 * Multiple floors, rows × cols spots per floor.
 * Spot types: COMPACT (2-wheeler), STANDARD (4-wheeler).
 * A 4-wheeler cannot park in a COMPACT spot; a 2-wheeler can park in either.
 *
 * Pattern cross-reference (docs/lld/):
 *   - Strategy (patterns_tier1.md #1): SpotSelectionStrategy — pluggable algorithm
 *     for choosing which spot to assign (nearest-to-entrance, fill-floor-first, etc.).
 *   - Factory Method (patterns_tier1.md #8): VehicleFactory creates typed vehicles.
 *   - State (patterns_tier1.md #7): ParkingSpot transitions EMPTY ↔ OCCUPIED.
 *   - Facade (patterns_tier1.md #12): ParkingLot is the facade; internal structure
 *     (floors, rows, columns) is hidden from the caller.
 *
 * Concurrency note (docs/lld/concurrency.md):
 *   The multi-threaded version uses a Semaphore per spot-type (not implemented
 *   here but referenced) to limit concurrent entries without locking the whole lot.
 *
 * Key methods implemented:
 *   1. park()   — find a suitable spot using the strategy, assign vehicle
 *   2. unpark() — free the spot, return the ticket
 *   3. getAvailability() — count free spots by type per floor
 */

// ─── Enums ────────────────────────────────────────────────────────────────────

enum VehicleType { TWO_WHEELER, FOUR_WHEELER }
enum SpotType    { COMPACT, STANDARD }        // COMPACT = 2-wheeler, STANDARD = 4-wheeler

// ─── Vehicle hierarchy ────────────────────────────────────────────────────────

abstract class Vehicle {
    final String      licensePlate;
    final VehicleType type;

    Vehicle(String licensePlate, VehicleType type) {
        this.licensePlate = licensePlate;
        this.type         = type;
    }
}

class TwoWheeler  extends Vehicle { TwoWheeler(String plate)  { super(plate, VehicleType.TWO_WHEELER); } }
class FourWheeler extends Vehicle { FourWheeler(String plate) { super(plate, VehicleType.FOUR_WHEELER); } }

// VehicleFactory (patterns_tier1.md #8)
class VehicleFactory {
    static Vehicle create(VehicleType type, String plate) {
        return switch (type) {
            case TWO_WHEELER   -> new TwoWheeler(plate);
            case FOUR_WHEELER  -> new FourWheeler(plate);
        };
    }
}

// ─── Parking Spot ─────────────────────────────────────────────────────────────

class ParkingSpot {
    final String   spotId;
    final int      floor;
    final int      row;
    final int      col;
    final SpotType spotType;
    Vehicle        parkedVehicle = null;   // null → EMPTY

    ParkingSpot(int floor, int row, int col, SpotType spotType) {
        this.floor    = floor;
        this.row      = row;
        this.col      = col;
        this.spotType = spotType;
        this.spotId   = String.format("F%d-R%d-C%d", floor, row, col);
    }

    boolean isEmpty() { return parkedVehicle == null; }

    /** A 2-wheeler fits in both COMPACT and STANDARD; a 4-wheeler only in STANDARD. */
    boolean canFit(Vehicle v) {
        if (!isEmpty()) return false;
        return v.type == VehicleType.TWO_WHEELER
            || spotType == SpotType.STANDARD;
    }

    void park(Vehicle v)  { parkedVehicle = v; }
    void free()           { parkedVehicle = null; }
}

// ─── Ticket ───────────────────────────────────────────────────────────────────

class ParkingTicket {
    private static int seq = 1;

    final String  ticketId;
    final String  spotId;
    final String  licensePlate;
    final long    entryTimeMs;

    ParkingTicket(ParkingSpot spot, Vehicle vehicle) {
        this.ticketId     = "TKT-" + (seq++);
        this.spotId       = spot.spotId;
        this.licensePlate = vehicle.licensePlate;
        this.entryTimeMs  = System.currentTimeMillis();
    }

    @Override public String toString() {
        return String.format("[%s] plate=%s spot=%s", ticketId, licensePlate, spotId);
    }
}

// ─── Spot Selection Strategy (Strategy pattern) ───────────────────────────────

interface SpotSelectionStrategy {
    Optional<ParkingSpot> selectSpot(List<List<ParkingSpot>> floors, Vehicle vehicle);
}

/** Fill from floor 0, row 0, col 0 — nearest-entrance heuristic. */
class NearestEntranceStrategy implements SpotSelectionStrategy {
    public Optional<ParkingSpot> selectSpot(List<List<ParkingSpot>> floors, Vehicle vehicle) {
        for (List<ParkingSpot> floorSpots : floors) {
            for (ParkingSpot spot : floorSpots) {
                if (spot.canFit(vehicle)) return Optional.of(spot);
            }
        }
        return Optional.empty();
    }
}

// ─── Parking Lot (Facade) ─────────────────────────────────────────────────────

public class ParkingLot {

    // floors[f] = list of spots on floor f (stored in row-major order)
    private final List<List<ParkingSpot>> floors;
    // ticketId → (ticket, spot) for O(1) unpark
    private final Map<String, ParkingSpot> ticketToSpot = new HashMap<>();
    private final Map<String, ParkingTicket> activeTickets = new HashMap<>();
    // license plate → active ticket (prevent same vehicle parked twice)
    private final Map<String, ParkingTicket> plateToTicket = new HashMap<>();

    private SpotSelectionStrategy strategy = new NearestEntranceStrategy();

    public ParkingLot(int numFloors, int rows, int cols) {
        this.floors = new ArrayList<>(numFloors);
        for (int f = 0; f < numFloors; f++) {
            List<ParkingSpot> floorSpots = new ArrayList<>(rows * cols);
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    // Simple rule: first two columns are COMPACT (2-wheeler), rest STANDARD
                    SpotType type = (c < 2) ? SpotType.COMPACT : SpotType.STANDARD;
                    floorSpots.add(new ParkingSpot(f, r, c, type));
                }
            }
            floors.add(floorSpots);
        }
    }

    public void setStrategy(SpotSelectionStrategy strategy) {
        this.strategy = strategy;
    }

    /**
     * 1. park() — assign a spot and issue a ticket.
     *
     * Delegates spot selection to the pluggable strategy.
     * O(F × S) worst case where F=floors, S=spots per floor.
     */
    public ParkingTicket park(VehicleType type, String licensePlate) {
        if (plateToTicket.containsKey(licensePlate))
            throw new IllegalStateException("Vehicle already parked: " + licensePlate);

        Vehicle vehicle = VehicleFactory.create(type, licensePlate);

        ParkingSpot spot = strategy.selectSpot(floors, vehicle)
            .orElseThrow(() -> new IllegalStateException("Parking lot is full"));

        spot.park(vehicle);

        ParkingTicket ticket = new ParkingTicket(spot, vehicle);
        ticketToSpot.put(ticket.ticketId, spot);
        activeTickets.put(ticket.ticketId, ticket);
        plateToTicket.put(licensePlate, ticket);

        return ticket;
    }

    /**
     * 2. unpark() — free the spot associated with the ticket.
     *
     * O(1) lookup via ticketToSpot map.
     */
    public ParkingTicket unpark(String ticketId) {
        ParkingTicket ticket = activeTickets.get(ticketId);
        if (ticket == null)
            throw new IllegalArgumentException("Invalid or already-used ticket: " + ticketId);

        ParkingSpot spot = ticketToSpot.get(ticketId);
        spot.free();

        activeTickets.remove(ticketId);
        ticketToSpot.remove(ticketId);
        plateToTicket.remove(ticket.licensePlate);

        return ticket;
    }

    /**
     * 3. getAvailability() — count free spots by type per floor.
     *
     * O(F × S) scan.
     */
    public void getAvailability() {
        for (int f = 0; f < floors.size(); f++) {
            long compact  = floors.get(f).stream()
                .filter(s -> s.isEmpty() && s.spotType == SpotType.COMPACT).count();
            long standard = floors.get(f).stream()
                .filter(s -> s.isEmpty() && s.spotType == SpotType.STANDARD).count();
            System.out.printf("Floor %d — COMPACT: %d free, STANDARD: %d free%n",
                f, compact, standard);
        }
    }

    // ── Demo ──────────────────────────────────────────────────────────────────
    public static void main(String[] args) {
        // 2 floors, 3 rows, 4 cols (cols 0-1 are COMPACT, cols 2-3 are STANDARD)
        ParkingLot lot = new ParkingLot(2, 3, 4);

        System.out.println("=== Initial availability ===");
        lot.getAvailability();

        ParkingTicket t1 = lot.park(VehicleType.TWO_WHEELER,  "MH01AB1234");
        ParkingTicket t2 = lot.park(VehicleType.FOUR_WHEELER, "MH02CD5678");
        ParkingTicket t3 = lot.park(VehicleType.TWO_WHEELER,  "MH03EF9012");

        System.out.println("\nParked: " + t1);
        System.out.println("Parked: " + t2);
        System.out.println("Parked: " + t3);

        System.out.println("\n=== After 3 parks ===");
        lot.getAvailability();

        lot.unpark(t2.ticketId);
        System.out.println("\nUnparked: " + t2);

        System.out.println("\n=== After unpark ===");
        lot.getAvailability();

        // Same vehicle again
        try {
            lot.park(VehicleType.TWO_WHEELER, "MH01AB1234");
        } catch (IllegalStateException e) {
            System.out.println("Expected: " + e.getMessage());
        }
    }
}
