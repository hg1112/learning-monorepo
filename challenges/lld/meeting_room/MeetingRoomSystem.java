package challenges.lld.meeting_room;

import java.util.*;

/**
 * Design a Meeting Room Reservation System
 *
 * Fixed list of rooms; support book/cancel with no overlapping bookings in the same room.
 *
 * Pattern cross-reference (docs/lld/):
 *   - State (patterns_tier1.md #7): Room transitions AVAILABLE ↔ BOOKED per time slot.
 *     Each Booking is itself a mini-State — CONFIRMED → CANCELLED.
 *   - Factory Method (patterns_tier1.md #8): BookingFactory creates typed bookings.
 *   - Builder (patterns_tier1.md #3): Booking built via Booking.Builder — many optional fields.
 *
 * Data structure (docs/data_structures/treemap_treeset.md):
 *   - TreeMap<startTime, Booking> per room — O(log n) insertion, predecessor/successor
 *     queries let us check overlap in one lowerEntry() + ceiling check.
 *
 * Key methods implemented:
 *   1. book()   — O(log n) overlap detection, O(log n) insertion
 *   2. cancel() — O(log n) removal
 *   3. listAvailable() — O(R log n) scan across all rooms for a given window
 */

// ─── Domain ───────────────────────────────────────────────────────────────────

class TimeSlot {
    final int startMinute;  // minutes since midnight, e.g. 9*60 = 540
    final int endMinute;

    TimeSlot(int startMinute, int endMinute) {
        if (startMinute >= endMinute) throw new IllegalArgumentException("start must be < end");
        this.startMinute = startMinute;
        this.endMinute   = endMinute;
    }

    boolean overlaps(TimeSlot other) {
        // Two intervals overlap iff neither is entirely before the other
        return this.startMinute < other.endMinute && other.startMinute < this.endMinute;
    }

    @Override public String toString() {
        return String.format("%02d:%02d–%02d:%02d",
            startMinute/60, startMinute%60, endMinute/60, endMinute%60);
    }
}

class Booking {
    private static int idSeq = 1;

    enum Status { CONFIRMED, CANCELLED }

    final String  bookingId;
    final String  roomId;
    final String  userId;
    final TimeSlot slot;
    Status status;

    // Builder pattern — avoids a 5-arg constructor (patterns_tier1.md #3)
    private Booking(Builder b) {
        this.bookingId = "BK-" + (idSeq++);
        this.roomId    = b.roomId;
        this.userId    = b.userId;
        this.slot      = b.slot;
        this.status    = Status.CONFIRMED;
    }

    static class Builder {
        private String roomId, userId;
        private TimeSlot slot;

        Builder room(String roomId)   { this.roomId = roomId; return this; }
        Builder user(String userId)   { this.userId = userId; return this; }
        Builder slot(TimeSlot slot)   { this.slot   = slot;   return this; }
        Booking build() {
            Objects.requireNonNull(roomId, "roomId required");
            Objects.requireNonNull(userId, "userId required");
            Objects.requireNonNull(slot,   "slot required");
            return new Booking(this);
        }
    }

    @Override public String toString() {
        return String.format("[%s] room=%s user=%s %s %s",
            bookingId, roomId, userId, slot, status);
    }
}

// ─── Room ─────────────────────────────────────────────────────────────────────

class Room {
    final String roomId;
    final int    capacity;

    // TreeMap keyed by startMinute — fast overlap detection
    // Invariant: no two confirmed bookings in this map overlap
    private final TreeMap<Integer, Booking> schedule = new TreeMap<>();

    Room(String roomId, int capacity) {
        this.roomId   = roomId;
        this.capacity = capacity;
    }

    /**
     * O(log n) — checks two neighbours in the sorted tree:
     *   1. The booking that starts just before our start (could overlap from the left)
     *   2. The booking that starts at/after our start (could overlap from the right)
     */
    boolean isAvailable(TimeSlot slot) {
        // Check left neighbour: its end might extend into our start
        Map.Entry<Integer, Booking> before = schedule.lowerEntry(slot.startMinute);
        if (before != null && before.getValue().slot.overlaps(slot)) return false;

        // Check right neighbour: its start might be before our end
        Map.Entry<Integer, Booking> after = schedule.ceilingEntry(slot.startMinute);
        if (after != null && after.getValue().slot.overlaps(slot)) return false;

        return true;
    }

    Booking addBooking(Booking booking) {
        schedule.put(booking.slot.startMinute, booking);
        return booking;
    }

    boolean removeBooking(String bookingId) {
        Iterator<Map.Entry<Integer, Booking>> it = schedule.entrySet().iterator();
        while (it.hasNext()) {
            Booking b = it.next().getValue();
            if (b.bookingId.equals(bookingId)) {
                b.status = Booking.Status.CANCELLED;
                it.remove();
                return true;
            }
        }
        return false;
    }

    List<Booking> getBookings() { return new ArrayList<>(schedule.values()); }
}

// ─── Reservation Service ──────────────────────────────────────────────────────

public class MeetingRoomSystem {

    private final Map<String, Room>    rooms    = new LinkedHashMap<>();
    private final Map<String, Booking> bookings = new HashMap<>();   // bookingId → Booking

    public MeetingRoomSystem(List<String> roomIds, int defaultCapacity) {
        for (String id : roomIds) rooms.put(id, new Room(id, defaultCapacity));
    }

    /**
     * 1. book() — core method, O(log n) per room.
     *
     * Returns the confirmed Booking, or throws if the room is busy.
     */
    public Booking book(String roomId, String userId, int startMin, int endMin) {
        Room room = rooms.get(roomId);
        if (room == null) throw new IllegalArgumentException("Unknown room: " + roomId);

        TimeSlot slot = new TimeSlot(startMin, endMin);
        if (!room.isAvailable(slot)) {
            throw new IllegalStateException(
                String.format("Room %s is already booked during %s", roomId, slot));
        }

        Booking b = new Booking.Builder()
            .room(roomId).user(userId).slot(slot).build();

        room.addBooking(b);
        bookings.put(b.bookingId, b);
        return b;
    }

    /**
     * 2. cancel() — O(log n).
     *
     * Marks the booking CANCELLED and frees the slot.
     */
    public void cancel(String bookingId) {
        Booking b = bookings.get(bookingId);
        if (b == null) throw new IllegalArgumentException("Unknown booking: " + bookingId);
        if (b.status == Booking.Status.CANCELLED)
            throw new IllegalStateException("Already cancelled: " + bookingId);

        rooms.get(b.roomId).removeBooking(bookingId);
        // Booking object stays in bookings map for audit trail
    }

    /**
     * 3. listAvailable() — returns all rooms free during the requested window.
     *
     * O(R log n) where R = number of rooms.
     */
    public List<String> listAvailable(int startMin, int endMin) {
        TimeSlot slot = new TimeSlot(startMin, endMin);
        List<String> result = new ArrayList<>();
        for (Room room : rooms.values()) {
            if (room.isAvailable(slot)) result.add(room.roomId);
        }
        return result;
    }

    public List<Booking> getRoomSchedule(String roomId) {
        Room r = rooms.get(roomId);
        if (r == null) throw new IllegalArgumentException("Unknown room: " + roomId);
        return r.getBookings();
    }

    // ── Demo ──────────────────────────────────────────────────────────────────
    public static void main(String[] args) {
        MeetingRoomSystem sys = new MeetingRoomSystem(
            Arrays.asList("Lemon", "Mango", "Cherry"), 10);

        Booking b1 = sys.book("Lemon", "alice", 540, 600);   // 09:00–10:00
        Booking b2 = sys.book("Lemon", "bob",   660, 720);   // 11:00–12:00
        Booking b3 = sys.book("Mango", "carol", 540, 720);   // 09:00–12:00

        System.out.println("Booked: " + b1);
        System.out.println("Booked: " + b2);
        System.out.println("Booked: " + b3);

        // Overlap attempt
        try {
            sys.book("Lemon", "dave", 570, 630);  // 09:30–10:30 — overlaps b1
        } catch (IllegalStateException e) {
            System.out.println("Expected conflict: " + e.getMessage());
        }

        // Available rooms at 09:00–10:00
        System.out.println("Available 09:00–10:00: " + sys.listAvailable(540, 600));
        // → [Cherry]

        // Cancel and rebook
        sys.cancel(b1.bookingId);
        System.out.println("After cancel, available 09:00–10:00: " + sys.listAvailable(540, 600));
        // → [Lemon, Cherry]
    }
}
