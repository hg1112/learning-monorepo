package challenges.lld.movie_booking;

import java.util.*;

/**
 * Design a Movie Ticket Booking System (BookMyShow)
 *
 * Cities → Cinemas → Screens → Shows → Seats
 * Users can book 1..N seats for a show.
 * Queries: cinemas in a city playing a movie; shows at a cinema for a movie.
 *
 * Pattern cross-reference (docs/lld/):
 *   - Facade (patterns_tier1.md #12): BookingService is the facade — one entry point
 *     hiding Cinema/Show/Seat subsystems.
 *   - Factory Method (patterns_tier1.md #8): ShowFactory creates Show objects with
 *     the right seat layout for the screen's configuration.
 *   - State (patterns_tier1.md #7): Seat transitions AVAILABLE → LOCKED → BOOKED
 *     (LOCKED is a short-lived state for payment processing; simplified here to 2 states).
 *   - Builder (patterns_tier1.md #3): Booking.Builder for the booking record.
 *
 * Concurrency note (docs/lld/concurrency.md):
 *   In a real system, seat locking uses Redis SETNX (distributed lock) or
 *   optimistic locking with DB version columns.  Here we use synchronized blocks
 *   on the Show object for clarity.
 *
 * Key methods implemented:
 *   1. addShow()            — add a show to a cinema screen
 *   2. bookSeats()          — atomic multi-seat booking with availability check
 *   3. getCinemasInCity()   — list cinemas showing a specific movie in a city
 */

// ─── Enums ────────────────────────────────────────────────────────────────────

enum SeatStatus { AVAILABLE, BOOKED }

// ─── Domain ───────────────────────────────────────────────────────────────────

class Seat {
    final String     seatId;     // e.g. "A1", "B12"
    final String     row;
    final int        number;
    SeatStatus       status = SeatStatus.AVAILABLE;

    Seat(String row, int number) {
        this.seatId  = row + number;
        this.row     = row;
        this.number  = number;
    }
}

class Show {
    final String          showId;
    final String          movieId;
    final String          screenId;
    final int             startTimeMin;   // minutes since midnight
    final Map<String, Seat> seats;        // seatId → Seat

    Show(String showId, String movieId, String screenId, int startTimeMin, int rows, int cols) {
        this.showId       = showId;
        this.movieId      = movieId;
        this.screenId     = screenId;
        this.startTimeMin = startTimeMin;
        this.seats        = buildSeats(rows, cols);
    }

    private Map<String, Seat> buildSeats(int rows, int cols) {
        Map<String, Seat> map = new LinkedHashMap<>();
        for (int r = 0; r < rows; r++) {
            String row = String.valueOf((char)('A' + r));
            for (int c = 1; c <= cols; c++) {
                Seat s = new Seat(row, c);
                map.put(s.seatId, s);
            }
        }
        return map;
    }

    List<Seat> availableSeats() {
        List<Seat> list = new ArrayList<>();
        for (Seat s : seats.values())
            if (s.status == SeatStatus.AVAILABLE) list.add(s);
        return list;
    }
}

class Screen {
    final String screenId;
    final int    rows;
    final int    cols;

    Screen(String screenId, int rows, int cols) {
        this.screenId = screenId;
        this.rows     = rows;
        this.cols     = cols;
    }
}

class Cinema {
    final String              cinemaId;
    final String              name;
    final String              city;
    final Map<String, Screen> screens = new LinkedHashMap<>();
    final Map<String, Show>   shows   = new LinkedHashMap<>();

    Cinema(String cinemaId, String name, String city) {
        this.cinemaId = cinemaId;
        this.name     = name;
        this.city     = city;
    }

    void addScreen(Screen screen) { screens.put(screen.screenId, screen); }

    void addShow(Show show) { shows.put(show.showId, show); }

    List<Show> getShowsForMovie(String movieId) {
        List<Show> result = new ArrayList<>();
        for (Show s : shows.values())
            if (s.movieId.equals(movieId)) result.add(s);
        return result;
    }
}

class Booking {
    private static int idSeq = 1;

    final String       bookingId;
    final String       userId;
    final String       showId;
    final List<String> seatIds;

    Booking(String userId, String showId, List<String> seatIds) {
        this.bookingId = "BKG-" + (idSeq++);
        this.userId    = userId;
        this.showId    = showId;
        this.seatIds   = Collections.unmodifiableList(new ArrayList<>(seatIds));
    }

    @Override public String toString() {
        return String.format("[%s] user=%s show=%s seats=%s",
            bookingId, userId, showId, seatIds);
    }
}

// ─── Booking Service (Facade) ─────────────────────────────────────────────────

public class MovieBookingSystem {

    private static int showIdSeq = 1;

    // city → list of cinema IDs
    private final Map<String, List<String>>  cityIndex  = new HashMap<>();
    private final Map<String, Cinema>        cinemas    = new HashMap<>();
    private final Map<String, Show>          allShows   = new HashMap<>();   // global showId lookup
    private final Map<String, Booking>       bookings   = new HashMap<>();

    // ── Setup ─────────────────────────────────────────────────────────────────

    public Cinema addCinema(String cinemaId, String name, String city) {
        Cinema c = new Cinema(cinemaId, name, city);
        cinemas.put(cinemaId, c);
        cityIndex.computeIfAbsent(city, k -> new ArrayList<>()).add(cinemaId);
        return c;
    }

    public Screen addScreen(String cinemaId, String screenId, int rows, int cols) {
        Cinema c = getCinema(cinemaId);
        Screen s = new Screen(screenId, rows, cols);
        c.addScreen(s);
        return s;
    }

    /**
     * 1. addShow() — register a new show on a screen; auto-creates the seat grid.
     *
     * Factory Method pattern: constructs a Show with the screen's row/col layout.
     * O(rows × cols) seat creation.
     */
    public Show addShow(String cinemaId, String screenId, String movieId, int startTimeMin) {
        Cinema c = getCinema(cinemaId);
        Screen s = c.screens.get(screenId);
        if (s == null) throw new IllegalArgumentException("Unknown screen: " + screenId);

        String showId = "SHW-" + (showIdSeq++);
        Show show = new Show(showId, movieId, screenId, startTimeMin, s.rows, s.cols);
        c.addShow(show);
        allShows.put(showId, show);
        return show;
    }

    /**
     * 2. bookSeats() — atomically verify and book a list of seats.
     *
     * Synchronized on the Show object to prevent double-booking under concurrency
     * (same pattern as Driver lock in docs/lld/concurrency.md — putIfAbsent / SETNX).
     *
     * O(k) where k = number of seats requested.
     */
    public Booking bookSeats(String userId, String showId, List<String> seatIds) {
        Show show = allShows.get(showId);
        if (show == null) throw new IllegalArgumentException("Unknown show: " + showId);

        synchronized (show) {
            // Validate all seats are available before booking any
            for (String sid : seatIds) {
                Seat seat = show.seats.get(sid);
                if (seat == null)
                    throw new IllegalArgumentException("Unknown seat: " + sid);
                if (seat.status != SeatStatus.AVAILABLE)
                    throw new IllegalStateException("Seat already booked: " + sid);
            }
            // All clear — mark as booked
            for (String sid : seatIds) {
                show.seats.get(sid).status = SeatStatus.BOOKED;
            }
        }

        Booking booking = new Booking(userId, showId, seatIds);
        bookings.put(booking.bookingId, booking);
        return booking;
    }

    /**
     * 3. getCinemasInCity() — list cinemas in a city showing a specific movie.
     *
     * O(C × S) where C = cinemas in city, S = shows per cinema.
     */
    public List<Cinema> getCinemasInCity(String city, String movieId) {
        List<String> cinemaIds = cityIndex.getOrDefault(city, Collections.emptyList());
        List<Cinema> result = new ArrayList<>();
        for (String cid : cinemaIds) {
            Cinema c = cinemas.get(cid);
            if (!c.getShowsForMovie(movieId).isEmpty()) result.add(c);
        }
        return result;
    }

    /** All shows at a cinema for a given movie. */
    public List<Show> getShowsAtCinema(String cinemaId, String movieId) {
        return getCinema(cinemaId).getShowsForMovie(movieId);
    }

    private Cinema getCinema(String id) {
        Cinema c = cinemas.get(id);
        if (c == null) throw new IllegalArgumentException("Unknown cinema: " + id);
        return c;
    }

    // ── Demo ──────────────────────────────────────────────────────────────────
    public static void main(String[] args) {
        MovieBookingSystem sys = new MovieBookingSystem();

        sys.addCinema("C1", "PVR Phoenix", "Mumbai");
        sys.addCinema("C2", "INOX Atria",  "Mumbai");
        sys.addCinema("C3", "Cinepolis",   "Delhi");

        sys.addScreen("C1", "S1", 5, 10);   // 50 seats
        sys.addScreen("C2", "S1", 4, 8);    // 32 seats

        Show show1 = sys.addShow("C1", "S1", "M001", 600);   // 10:00
        Show show2 = sys.addShow("C1", "S1", "M001", 840);   // 14:00
        Show show3 = sys.addShow("C2", "S1", "M001", 720);   // 12:00
        Show show4 = sys.addShow("C2", "S1", "M002", 600);   // different movie

        // Cinemas in Mumbai showing M001
        List<Cinema> available = sys.getCinemasInCity("Mumbai", "M001");
        System.out.println("Cinemas in Mumbai showing M001:");
        available.forEach(c -> System.out.println("  " + c.name));

        // Book seats
        Booking b = sys.bookSeats("user1", show1.showId,
            Arrays.asList("A1", "A2", "A3"));
        System.out.println("Booking: " + b);

        // Try to double-book
        try {
            sys.bookSeats("user2", show1.showId, Arrays.asList("A1"));
        } catch (IllegalStateException e) {
            System.out.println("Expected: " + e.getMessage());
        }

        // Available seats in show1
        System.out.println("Available seats: " + show1.availableSeats().size());   // 47
    }
}
