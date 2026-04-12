# Design Patterns — Tier 1 (Must Know)

Patterns asked in virtually every LLD interview. Master these first.

← Back to [Design Patterns Index](design_patterns.md)

---

## 1. Strategy

**Intent:** Define a family of algorithms, encapsulate each one, make them interchangeable.

```java
@FunctionalInterface
interface MatchingStrategy {
    Optional<Driver> match(Rider rider, List<Driver> candidates);
}

// Strategies
MatchingStrategy nearestFirst    = (rider, drivers) ->
    drivers.stream().min(Comparator.comparingDouble(d -> distance(rider, d)));

MatchingStrategy highestRated    = (rider, drivers) ->
    drivers.stream().max(Comparator.comparingDouble(Driver::getRating));

MatchingStrategy optimalBatching = (rider, drivers) ->
    ORToolsMatcher.solve(rider, drivers);  // Uber's real approach

// Context
class MatchingEngine {
    private MatchingStrategy strategy;

    public MatchingEngine(MatchingStrategy strategy) { this.strategy = strategy; }
    public void setStrategy(MatchingStrategy s)      { this.strategy = s; }

    public Optional<Driver> findDriver(Rider rider, List<Driver> nearby) {
        return strategy.match(rider, nearby);
    }
}
```

**Uber backend:** Swappable matching strategies (basic for dev, optimal for prod).
Also fare calculation strategies (UberX vs Pool vs Black, surge multiplier strategies).

---

## 2. Observer

**Intent:** When an object changes state, all its dependents are notified automatically.

```java
interface LocationObserver {
    void onLocationUpdate(String driverId, LatLng location);
}

class LocationPublisher {
    private final List<LocationObserver> observers = new CopyOnWriteArrayList<>();

    public void subscribe(LocationObserver o)   { observers.add(o); }
    public void unsubscribe(LocationObserver o) { observers.remove(o); }

    public void updateLocation(String driverId, LatLng loc) {
        redisGeo.add(driverId, loc);
        observers.forEach(o -> o.onLocationUpdate(driverId, loc));
    }
}

class RiderMapPushObserver implements LocationObserver {
    public void onLocationUpdate(String driverId, LatLng loc) {
        wsSession.sendMessage(new TextMessage(toJson(loc)));
    }
}

class SurgeCalculatorObserver implements LocationObserver {
    public void onLocationUpdate(String driverId, LatLng loc) {
        surgeEngine.recalculate(loc.h3Cell());
    }
}
```

**Uber backend:** WebSocket push to rider when assigned driver's location updates.
Kafka is the distributed Observer pattern — producers publish, any number of consumers subscribe.

---

## 3. Builder

**Intent:** Construct a complex object step by step. Separates construction from representation.

```java
public class RideOffer {
    private final String driverId;
    private final String vehicleType;
    private final double estimatedFare;
    private final int etaMinutes;
    private final double driverRating;

    private RideOffer(Builder b) {
        this.driverId      = b.driverId;
        this.vehicleType   = b.vehicleType;
        this.estimatedFare = b.estimatedFare;
        this.etaMinutes    = b.etaMinutes;
        this.driverRating  = b.driverRating;
    }

    public static class Builder {
        private String driverId;
        private String vehicleType = "UberX";  // defaults
        private double estimatedFare;
        private int etaMinutes;
        private double driverRating;

        public Builder driverId(String id)     { this.driverId = id; return this; }
        public Builder vehicleType(String t)    { this.vehicleType = t; return this; }
        public Builder estimatedFare(double f)  { this.estimatedFare = f; return this; }
        public Builder eta(int minutes)         { this.etaMinutes = minutes; return this; }
        public Builder driverRating(double r)   { this.driverRating = r; return this; }
        public RideOffer build() {
            Objects.requireNonNull(driverId, "driverId required");
            return new RideOffer(this);
        }
    }
}

// Usage
RideOffer offer = new RideOffer.Builder()
    .driverId("driver-123")
    .estimatedFare(12.50)
    .eta(4)
    .driverRating(4.8)
    .build();
```

**Uber backend:** Building `RideOffer` and `TripSummary` objects with many optional fields.
Also `proto` message builders in gRPC (Protobuf uses the Builder pattern natively).

---

## 4. Singleton

**Intent:** Ensure a class has only one instance and provide global access.

```java
public class RedisClient {
    private static volatile RedisClient instance;
    private final JedisPool pool;

    private RedisClient() {
        pool = new JedisPool("localhost", 6379);
    }

    public static RedisClient getInstance() {
        if (instance == null) {
            synchronized (RedisClient.class) {
                if (instance == null) {      // double-checked locking
                    instance = new RedisClient();
                }
            }
        }
        return instance;
    }

    public Jedis getConnection() { return pool.getResource(); }
}
```

`volatile` prevents the JVM from reordering the assignment before the constructor
completes (Java Memory Model guarantee).

**When to use:** Shared resources (DB connections, config, loggers, caches).
**Pitfall:** Hides dependencies, makes testing hard. Prefer DI (Spring `@Bean`) in production.
**Uber backend:** Redis connection pool — one `JedisPool` shared across all requests.

---

## 5. Decorator

**Intent:** Add responsibilities to objects dynamically by wrapping them.

```java
interface MatchingService {
    Optional<Driver> findMatch(Rider rider);
}

class BasicMatchingService implements MatchingService {
    public Optional<Driver> findMatch(Rider rider) {
        // query Redis GEORADIUS, return nearest driver
    }
}

// Adds metrics logging without modifying BasicMatchingService
class MetricsMatchingDecorator implements MatchingService {
    private final MatchingService delegate;
    private final MeterRegistry registry;

    public Optional<Driver> findMatch(Rider rider) {
        Timer.Sample sample = Timer.start(registry);
        Optional<Driver> result = delegate.findMatch(rider);
        sample.stop(registry.timer("matching.latency"));
        registry.counter("matching.attempts").increment();
        if (result.isEmpty()) registry.counter("matching.misses").increment();
        return result;
    }
}

// Adds retry logic
class RetryMatchingDecorator implements MatchingService {
    private final MatchingService delegate;
    private final int maxRadius;

    public Optional<Driver> findMatch(Rider rider) {
        for (int radius = 1; radius <= maxRadius; radius *= 2) {
            rider.setSearchRadius(radius);
            Optional<Driver> result = delegate.findMatch(rider);
            if (result.isPresent()) return result;
        }
        return Optional.empty();
    }
}
```

**Stacking decorators:**
```java
MatchingService service = new RetryMatchingDecorator(
    new MetricsMatchingDecorator(
        new BasicMatchingService(redisClient)), maxRadius);
```

**Uber backend:** Layered interceptors on gRPC services (auth → rate-limit → metrics → handler).
Spring's `@Transactional`, Spring Security, and WebSocket handlers all use the Decorator pattern.

---

## 6. Proxy

**Intent:** Provide a surrogate that controls access to another object.

Types:
- **Remote Proxy:** gRPC stub — local object that calls a remote service
- **Virtual Proxy:** lazy-load an expensive resource
- **Protection Proxy:** check permissions before forwarding

```java
// Protection proxy — check authorization before processing ride request
public class SecureRideService implements RideService {
    private final RideService delegate;
    private final AuthService auth;

    public Ride bookRide(String userId, RideRequest req) {
        if (!auth.hasPermission(userId, "BOOK_RIDE"))
            throw new UnauthorizedException("User not authorized");
        return delegate.bookRide(userId, req);
    }
}
```

**Uber backend:** gRPC stubs are remote proxies. Spring's `@Transactional` creates a proxy
around beans. Spring Security wraps controllers in protection proxies.

---

## 7. State

**Intent:** Allow an object to alter its behavior when its internal state changes.

```java
interface DriverState {
    void requestRide(DriverContext ctx, String riderId);
    void acceptRide(DriverContext ctx, String rideId);
    void completeRide(DriverContext ctx);
}

class AvailableState implements DriverState {
    public void requestRide(DriverContext ctx, String riderId) {
        ctx.notifyDriver(riderId);
        ctx.setState(new PendingAcceptanceState());
    }
    public void acceptRide(DriverContext ctx, String rideId) { /* invalid in this state */ }
    public void completeRide(DriverContext ctx) { /* invalid */ }
}

class OnTripState implements DriverState {
    public void requestRide(DriverContext ctx, String riderId) { /* reject — busy */ }
    public void acceptRide(DriverContext ctx, String rideId)   { /* invalid */ }
    public void completeRide(DriverContext ctx) {
        ctx.setState(new AvailableState());
        ctx.updateRedisStatus("AVAILABLE");
    }
}

class DriverContext {
    private DriverState state = new AvailableState();
    public void setState(DriverState s) { this.state = s; }
    public void requestRide(String riderId) { state.requestRide(this, riderId); }
    public void completeRide()             { state.completeRide(this); }
}
```

**Uber backend:** Driver FSM — AVAILABLE → MATCHED → ON_TRIP → AVAILABLE.
Ride FSM — REQUESTED → MATCHED → IN_PROGRESS → COMPLETED (or CANCELLED).

---

## 8. Factory Method

**Intent:** Define an interface for creating objects, letting subclasses decide which class to instantiate.

```java
interface RideRequest { double calculateFare(double distanceKm); }

class UberXRequest    implements RideRequest { public double calculateFare(double d) { return 1.50 + d * 0.90; } }
class UberPoolRequest implements RideRequest { public double calculateFare(double d) { return 0.75 + d * 0.60; } }
class UberBlackRequest implements RideRequest { public double calculateFare(double d) { return 5.00 + d * 2.50; } }

class RideRequestFactory {
    public static RideRequest create(String rideType) {
        return switch (rideType) {
            case "UberX"  -> new UberXRequest();
            case "Pool"   -> new UberPoolRequest();
            case "Black"  -> new UberBlackRequest();
            default -> throw new IllegalArgumentException("Unknown ride type: " + rideType);
        };
    }
}
```

**Uber backend:** Creating ride objects with different fare models. Also used in gRPC
service factories — different stub implementations for test vs production.

---

## 9. Command

**Intent:** Encapsulate a request as an object, allowing parameterization, queuing, logging, and undo.

```java
interface Command {
    void execute();
    void undo();
}

class BookRideCommand implements Command {
    private final RideRepository repo;
    private final Ride ride;
    private String rideId;

    public void execute() {
        rideId = repo.save(ride);
        notificationService.notifyDriver(ride.getDriverId(), ride);
    }

    public void undo() {
        repo.cancel(rideId);
        notificationService.notifyDriver(ride.getDriverId(), "Ride cancelled");
    }
}

// Command queue — decouples sender from executor
BlockingQueue<Command> commandQueue = new LinkedBlockingQueue<>();
commandQueue.put(new BookRideCommand(repo, ride));  // producer
Command cmd = commandQueue.take(); cmd.execute();   // consumer (another thread)
```

**Uber backend:** Temporal workflows are commands that can be retried, compensated (Saga),
and inspected. Kafka messages are serialized commands.

---

## 10. Chain of Responsibility

**Intent:** Pass a request along a chain of handlers; each handler decides to process or pass along.

```java
abstract class RequestHandler {
    protected RequestHandler next;
    public RequestHandler setNext(RequestHandler next) { this.next = next; return next; }
    public abstract void handle(RideRequest req);
}

class AuthHandler extends RequestHandler {
    public void handle(RideRequest req) {
        if (!authService.isValid(req.getToken())) throw new AuthException();
        if (next != null) next.handle(req);
    }
}

class RateLimitHandler extends RequestHandler {
    public void handle(RideRequest req) {
        if (rateLimiter.isThrottled(req.getUserId())) throw new TooManyRequestsException();
        if (next != null) next.handle(req);
    }
}

class FraudCheckHandler extends RequestHandler {
    public void handle(RideRequest req) {
        if (fraudService.isSuspicious(req)) throw new FraudException();
        if (next != null) next.handle(req);
    }
}

// Wire the chain
RequestHandler chain = new AuthHandler();
chain.setNext(new RateLimitHandler()).setNext(new FraudCheckHandler());
chain.handle(request);
```

**Uber backend:** gRPC interceptor pipeline. Spring Security filter chain.
API Gateway middleware (auth → rate-limit → routing → service).

---

## 11. Adapter

**Intent:** Convert the interface of a class into one clients expect. Makes incompatible interfaces work together.

```java
// Legacy location service returns lat/lng as a String "lat,lng"
interface LegacyLocationService { String getLocation(String driverId); }
interface LocationService       { LatLng getLocation(String driverId); }

class LocationServiceAdapter implements LocationService {
    private final LegacyLocationService legacy;
    public LocationServiceAdapter(LegacyLocationService legacy) { this.legacy = legacy; }

    public LatLng getLocation(String driverId) {
        String raw = legacy.getLocation(driverId);  // "37.7749,-122.4194"
        String[] parts = raw.split(",");
        return new LatLng(Double.parseDouble(parts[0]), Double.parseDouble(parts[1]));
    }
}
```

**Uber backend:** Adapting between gRPC `LocationProto` messages and internal `LatLng` domain objects.
Also wrapping Redis GEO commands behind a `GeoStore` interface.

---

## 12. Facade

**Intent:** Provide a simplified interface to a complex subsystem.

```java
// Subsystem: Redis GEO, Cassandra driver metadata, H3 hex indexing
public class NearbyDriverFacade {
    private final RedisGeoClient geo;
    private final DriverRepository driverRepo;
    private final H3Index h3;

    public List<DriverSummary> findNearby(double lat, double lng, double radiusKm) {
        List<String> cells     = h3.kRing(lat, lng, radiusKm);
        List<String> driverIds = geo.radius(lat, lng, radiusKm);
        return driverRepo.findByIds(driverIds);
    }
}
```

**Uber backend:** `LocationService` is a facade over Redis GEO and Kafka.
`BookingService` is a facade over ride DB, payment, notification, and driver status updates.
