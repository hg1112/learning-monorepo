# Design Patterns — Large-Scale Architecture

Beyond GoF — these come up in every senior/principal system design interview.
Ordered from most operationally critical to more architectural/strategic.

← Back to [Design Patterns Index](design_patterns.md)

---

## 22. Circuit Breaker

**Intent:** Prevent a service from repeatedly calling a failing dependency. After a threshold
of failures, "open" the circuit and return a fallback immediately. Periodically allow a
probe request through to check if the dependency has recovered.

```
CLOSED ──(failures ≥ threshold)──► OPEN ──(timeout expires)──► HALF-OPEN
  ▲                                                                  │
  └─────────────(probe succeeds)────────────────────────────────────┘
                                        │
                          (probe fails)─▼
                                      OPEN (reset timer)
```

| State | Behavior |
|-------|----------|
| **CLOSED** | Requests pass through. Count consecutive failures. |
| **OPEN** | Requests fail immediately (no network call). Return fallback. |
| **HALF-OPEN** | Let one probe request through. Success → CLOSED. Failure → OPEN. |

```java
public class CircuitBreaker {
    public enum State { CLOSED, OPEN, HALF_OPEN }

    private State state = State.CLOSED;
    private int failureCount = 0;
    private long openedAt = 0;

    private final int failureThreshold;
    private final long timeoutMs;

    public <T> T execute(Supplier<T> action, Supplier<T> fallback) {
        if (state == State.OPEN) {
            if (System.currentTimeMillis() - openedAt >= timeoutMs) {
                state = State.HALF_OPEN;
            } else {
                return fallback.get();    // fail fast
            }
        }
        try {
            T result = action.get();
            onSuccess();
            return result;
        } catch (Exception e) {
            onFailure();
            return fallback.get();
        }
    }

    private synchronized void onSuccess() { failureCount = 0; state = State.CLOSED; }
    private synchronized void onFailure() {
        failureCount++;
        if (state == State.HALF_OPEN || failureCount >= failureThreshold) {
            state = State.OPEN;
            openedAt = System.currentTimeMillis();
        }
    }
}
```

```java
// Resilience4j (production)
CircuitBreakerConfig config = CircuitBreakerConfig.custom()
    .failureRateThreshold(50)
    .waitDurationInOpenState(Duration.ofSeconds(30))
    .slidingWindowSize(10)
    .build();
CircuitBreaker cb = CircuitBreaker.of("paymentService", config);
```

**Where circuit breakers apply in Uber backend:**

| Call | What breaks | Fallback |
|------|-------------|---------|
| `stripeClient.charge()` | Payment provider outage | Queue for async retry |
| `notificationService.push()` | Push service down | Skip, log for retry |
| `geocodingService.reverse()` | Geocoding rate-limited | Return raw coordinates |
| `fraudService.check()` | Fraud ML slow | Default allow (with flag) |

**Relationship to Retry + Timeout:**
```
Request → [Timeout] → [Retry] → [Circuit Breaker] → Dependency

Timeout:         don't wait forever for one call
Retry:           try again on transient failure
Circuit Breaker: stop retrying when failure rate is high → gives dependency breathing room
```

---

## 23. Retry with Exponential Backoff + Jitter

**Intent:** Retry failed calls with increasing delays to avoid overwhelming a recovering service.
Jitter randomises delays to prevent thundering herd.

```
Without jitter:                With full jitter:
t=1s: ALL clients retry        t=1.0s: client A retries
t=2s: ALL clients retry        t=1.3s: client B retries
t=4s: ALL clients retry        t=1.7s: client C retries
→ spike hits recovering svc    → load is spread
```

```java
public <T> T withRetry(Supplier<T> action, int maxAttempts) throws Exception {
    int attempt = 0;
    long delayMs = 100;
    Random random = new Random();

    while (true) {
        try {
            return action.get();
        } catch (TransientException e) {
            if (++attempt >= maxAttempts) throw e;
            long jitteredDelay = (long)(random.nextDouble() * delayMs);  // full jitter
            Thread.sleep(jitteredDelay);
            delayMs = Math.min(delayMs * 2, 30_000);  // cap at 30s
        }
    }
}
```

**Jitter strategies:**
- **Full jitter:** `sleep = random(0, cap)` — most effective at spreading load
- **Equal jitter:** `sleep = cap/2 + random(0, cap/2)`
- **Decorrelated:** `sleep = random(base, prev × 3)` — avoids synchronized clusters

**Only retry idempotent operations.** Don't retry `POST /charge` without idempotency keys.

---

## 24. Bulkhead

**Intent:** Isolate components so a failure in one doesn't cascade to others.
Named after ship bulkheads — watertight compartments that contain flooding.

```
Without Bulkhead:            With Bulkhead:
  All calls share             Separate thread pools per dependency
  one thread pool
  ┌─────────────┐            ┌──────────┐  ┌──────────┐  ┌──────────┐
  │  Payments   │            │ Payments │  │ Geocoding│  │  Fraud   │
  │  Geocoding  │            │ pool(10) │  │ pool(5)  │  │ pool(3)  │
  │  Fraud      │            └──────────┘  └──────────┘  └──────────┘
  │  pool(20)   │
  └─────────────┘
  Fraud hangs → fills pool   Fraud hangs → only its pool fills
  → Payments blocked too     → Payments unaffected
```

```java
ExecutorService paymentPool = new ThreadPoolExecutor(10, 10, 0, SECONDS,
    new ArrayBlockingQueue<>(50), new ThreadPoolExecutor.AbortPolicy());
ExecutorService fraudPool   = new ThreadPoolExecutor(3, 3, 0, SECONDS,
    new ArrayBlockingQueue<>(10), new ThreadPoolExecutor.AbortPolicy());

CompletableFuture<PaymentResult> payment =
    CompletableFuture.supplyAsync(() -> stripeClient.charge(userId, fare), paymentPool);
```

**Bulkhead + Circuit Breaker:** Bulkhead limits concurrent calls; Circuit Breaker stops
calls entirely when failure rate is high. Use both together.

---

## 25. Saga Pattern (Distributed Transactions)

**Intent:** Manage distributed transactions across microservices using a series of local
transactions, each with a compensating transaction on failure. No distributed 2PC needed.

```
Booking Saga Steps:
1. Create ride record in DB          (compensation: DELETE ride)
2. Lock driver in Redis              (compensation: RELEASE driver lock)
3. Charge payment method             (compensation: REFUND payment)
4. Send notification to driver       (compensation: CANCEL notification)
5. Mark ride as CONFIRMED
```

**Choreography (event-driven):** Each service publishes events; the next service listens.
Simple but hard to trace failure flow.

**Orchestration (Temporal):** A central workflow coordinates all steps.

```java
public class BookingWorkflowImpl implements BookingWorkflow {
    private final RideActivity    rideActivity    = Workflow.newActivityStub(RideActivity.class);
    private final PaymentActivity paymentActivity = Workflow.newActivityStub(PaymentActivity.class);

    public BookingResult bookRide(BookingRequest req) {
        String rideId = rideActivity.createRide(req);
        try {
            paymentActivity.charge(req.getUserId(), req.getFare());
        } catch (PaymentException e) {
            rideActivity.cancelRide(rideId);  // compensating transaction
            return BookingResult.failed("Payment failed");
        }
        return BookingResult.success(rideId);
    }
}
```

**Uber backend:** Temporal Saga for double-booking prevention and booking workflow.
See `docs/system_design/uber/rides.md` — Level 3 architecture.

---

## 26. CQRS — Command Query Responsibility Segregation

**Intent:** Use separate models for reads and writes. The write side (Command) optimises
for consistency; the read side (Query) optimises for query performance.

```
Client
  │
  ├─ Commands (write) ──► Command Handler ──► Write DB (normalized, ACID)
  │                              │
  │                         Domain Event ──► Event Bus
  │                                               │
  └─ Queries (read)  ──► Query Handler  ◄── Read DB (denormalized, fast)
                                               ▲
                                         Projection (updates read DB from events)
```

```java
// Write side — enforces business rules
@Service
class BookRideCommandHandler {
    public void handle(BookRideCommand cmd) {
        Ride ride = Ride.create(cmd.getRiderId(), driver.getId(), cmd.getPickup());
        rideWriteRepo.save(ride);
        eventBus.publish(new RideBookedEvent(ride.getId(), driver.getId()));
    }
}

// Read side — denormalized view updated by projection
@Service
class RideProjection {
    @EventHandler
    public void on(RideBookedEvent e) {
        rideReadRepo.upsert(new RideSummaryView(e.getRideId(), e.getDriverId(), "IN_PROGRESS"));
    }
}

// Query — fast single-index read
@Service
class RideQueryHandler {
    public List<RideSummaryView> getActiveRides(String userId) {
        return rideReadRepo.findByUserId(userId);
    }
}
```

**Trade-offs:** Eventual consistency between write and read models. More operational complexity.
**Real-world:** Uber ride history (write: PostgreSQL; read: Cassandra for timeline queries).

---

## 27. Event Sourcing

**Intent:** Store state changes as an immutable log of events rather than storing current state.
Current state is derived by replaying events.

```
Traditional:    rides table          Event Sourcing: ride_events table
                ─────────────                        ──────────────────────────
                id | status                          rideId | event         | ts
                1  | COMPLETED  ←                   r-1    | RideRequested | t1
                                                    r-1    | DriverMatched | t2
                                                    r-1    | RideCompleted | t3
                                                    ↑ append-only, never update/delete
```

```java
sealed interface RideEvent permits RideRequested, DriverMatched, RideCompleted {}
record RideRequested(String rideId, String userId, LatLng pickup)   implements RideEvent {}
record DriverMatched(String rideId, String driverId, int eta)        implements RideEvent {}
record RideCompleted(String rideId, double fare, Instant completedAt) implements RideEvent {}

class RideAggregate {
    private String status = "NONE";

    public static RideAggregate rebuild(List<RideEvent> events) {
        RideAggregate r = new RideAggregate();
        events.forEach(r::apply);
        return r;
    }

    private void apply(RideEvent e) {
        switch (e) {
            case RideRequested r -> status = "REQUESTED";
            case DriverMatched r -> status = "MATCHED";
            case RideCompleted r -> status = "COMPLETED";
        }
    }
}
```

**Benefits:** Complete audit trail, temporal queries ("what was the state at 3pm yesterday?"),
natural fit for CQRS.
**Trade-offs:** Rebuilding state requires replay (mitigate with periodic snapshots). Schema evolution is hard.
**Real-world:** Banking ledgers, Git commits, Uber trip audit logs.

---

## 28. Outbox Pattern

**Intent:** Guarantee that a DB write and a message publish happen atomically,
without a distributed transaction. Solves the "dual-write" problem.

**Problem:**
```
db.save(ride);               // succeeds
kafka.publish(RideCreated);  // crashes → event is lost
```

**Solution:** Write the event to an `outbox` table in the same DB transaction.
A separate relay process publishes from the outbox to Kafka.

```java
@Transactional
public void bookRide(BookRideCommand cmd) {
    Ride ride = Ride.create(cmd);
    rideRepo.save(ride);
    outboxRepo.save(OutboxEvent.of("RideBooked", serialize(ride)));
    // Both committed atomically — either both succeed or both fail
}

@Scheduled(fixedDelay = 100)
public void relay() {
    for (OutboxEvent e : outboxRepo.findUnpublished()) {
        kafka.publish(e.getTopic(), e.getPayload());
        outboxRepo.markPublished(e.getId());   // idempotent
    }
}
```

**With CDC (Debezium):** Stream PostgreSQL WAL changes directly to Kafka — lower latency,
no polling loop needed.

---

## 29. Backend for Frontend (BFF)

**Intent:** Create a separate backend API layer tailored to each client type
(mobile, web, third-party). Each frontend gets an API shaped for its specific needs.

```
            ┌─────────────────────────────────────────┐
            │            Downstream Services           │
            │  Rides  │  Drivers  │  Payments  │  Maps │
            └──────────────────────────────────────────┘
                 ▲           ▲            ▲
    ┌────────────┘  ┌─────────┘  ┌────────┘
┌───┴──────┐  ┌────┴─────┐  ┌───┴──────┐
│Mobile BFF│  │ Web BFF  │  │ Partner  │
│(iOS/Andr)│  │(React)   │  │  BFF     │
└──────────┘  └──────────┘  └──────────┘
```

```java
// Mobile BFF — compact response for bandwidth/battery
@RestController("/mobile/v1/trips")
class MobileTripBFF {
    public MobileTripSummary getTrip(String tripId) {
        Trip trip     = tripService.getTrip(tripId);
        Driver driver = driverService.getDriver(trip.getDriverId());
        return MobileTripSummary.builder()
            .eta(trip.getEta())
            .driverName(driver.getFirstName())   // first name only
            .driverRating(driver.getRating())
            .vehiclePlate(driver.getPlate())     // 3 fields vs 30 in full profile
            .build();
    }
}
```

**Why not one API?** Mobile: compact payloads. Web: full data for rich UI. Partner: stable versioned contracts.
**Real-world:** Netflix (one BFF per device type), Uber (rider app, driver app, Eats all differ).

---

## 30. Sidecar

**Intent:** Attach a helper container alongside the main application in the same pod/host.
The sidecar handles cross-cutting concerns without the app knowing.

```
┌─────────────────────────────────────────┐
│  Pod                                    │
│  ┌────────────────┐  ┌───────────────┐  │
│  │  App Container │  │   Sidecar     │  │
│  │  (Ride Service)│  │  (Envoy Proxy)│  │
│  │  business logic│  │  mTLS         │  │
│  │  port 8080     │  │  metrics      │  │
│  │                │  │  tracing      │  │
│  └────────────────┘  │  retries      │  │
│   shared network /   │  rate-limit   │  │
│   localhost          └───────────────┘  │
└─────────────────────────────────────────┘
```

**Common sidecar responsibilities:**
- **Service mesh proxy** (Envoy/Istio): mTLS, load balancing, retries, circuit breaking
- **Log shipping** (Fluentd/Filebeat): tail app logs, forward to ELK
- **Metrics scraping** (Prometheus exporter): expose `/metrics` endpoint
- **Secret rotation** (Vault agent): inject and refresh secrets without app restart

**Real-world:** Uber, Netflix, Google all use Envoy sidecars via Istio service mesh.

---

## 31. Strangler Fig

**Intent:** Migrate a monolith to microservices incrementally. New capabilities are
built as separate services; monolith functionality is gradually replaced.

```
Phase 1:          Phase 2:              Phase 3:
                  ┌──────────────┐      ┌──────────────┐
 ┌──────────┐     │ API Gateway  │      │ API Gateway  │
 │ Monolith │     └──────┬───────┘      └──┬───────┬───┘
 │  /rides  │          ↓   ↓              ↓       ↓
 │  /drivers│    ┌────────┐ ┌───────┐  ┌─────┐  ┌──────┐
 │  /payment│    │Monolith│ │Payment│  │Rides│  │Drivers│
 └──────────┘    │/rides  │ │Service│  │Svc  │  │Svc   │
                 │/drivers│ └───────┘  └─────┘  └──────┘
                 └────────┘                 Monolith gone
```

1. Put an API Gateway in front of the monolith
2. Build new microservice for one capability
3. Route that traffic to the new service, all else to monolith
4. Repeat until monolith is empty, then decommission

**Key tool:** Feature flags to shift traffic % gradually, enabling safe rollback.
**Real-world:** Uber's migration from Python monolith (2014–2016). Amazon API mandates memo.
