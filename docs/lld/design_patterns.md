# Design Patterns — Index

Reference for LLD and system design interviews. 31 patterns split into focused files.

**Ordering:** Most commonly asked first within each tier.

---

## Files

| File | Contents | Patterns |
|------|----------|---------|
| [patterns_tier1.md](patterns_tier1.md) | Must-know GoF patterns | 1–12 |
| [patterns_tier2.md](patterns_tier2.md) | Commonly asked + specialized GoF | 13–21 |
| [patterns_distributed.md](patterns_distributed.md) | Large-scale architecture patterns | 22–31 |

---

## Pattern Summary Table

| # | Pattern | Category | Example Usage | Interview Frequency |
|---|---------|----------|--------------|---------------------|
| 1 | [Strategy](patterns_tier1.md#1-strategy) | Behavioral | Matching algorithm, fare calculation | **High** |
| 2 | [Observer](patterns_tier1.md#2-observer) | Behavioral | WebSocket push, Kafka pub/sub | **High** |
| 3 | [Builder](patterns_tier1.md#3-builder) | Creational | RideOffer, proto messages | **High** |
| 4 | [Singleton](patterns_tier1.md#4-singleton) | Creational | Redis/DB connection pool | **High** |
| 5 | [Decorator](patterns_tier1.md#5-decorator) | Structural | gRPC interceptors, metrics wrapping | **High** |
| 6 | [Proxy](patterns_tier1.md#6-proxy) | Structural | gRPC stubs, Spring AOP (@Transactional) | **High** |
| 7 | [State](patterns_tier1.md#7-state) | Behavioral | Driver FSM, Ride FSM | **High** |
| 8 | [Factory Method](patterns_tier1.md#8-factory-method) | Creational | Ride type creation | **High** |
| 9 | [Command](patterns_tier1.md#9-command) | Behavioral | Kafka messages, Temporal activities | **High** |
| 10 | [Chain of Responsibility](patterns_tier1.md#10-chain-of-responsibility) | Behavioral | gRPC/HTTP interceptor pipeline | **High** |
| 11 | [Adapter](patterns_tier1.md#11-adapter) | Structural | Proto ↔ domain object conversion | **High** |
| 12 | [Facade](patterns_tier1.md#12-facade) | Structural | LocationService, BookingService | **High** |
| 13 | [Template Method](patterns_tier2.md#13-template-method) | Behavioral | Trip processing workflow skeleton | Medium |
| 14 | [Mediator](patterns_tier2.md#14-mediator) | Behavioral | Event bus, chat room, Kafka | Medium |
| 15 | [Composite](patterns_tier2.md#15-composite) | Structural | Uber Eats menu hierarchy | Medium |
| 16 | [Bridge](patterns_tier2.md#16-bridge) | Structural | Notification type × delivery channel | Medium |
| 17 | [Abstract Factory](patterns_tier2.md#17-abstract-factory) | Creational | Prod DB vs test DB family | Medium |
| 18 | [Visitor](patterns_tier2.md#18-visitor) | Behavioral | Fare components, AST processing | Medium |
| 19 | [Memento](patterns_tier2.md#19-memento) | Behavioral | Undo/redo, transaction savepoints | Medium |
| 20 | [Flyweight](patterns_tier2.md#20-flyweight) | Structural | Map pin icons, String interning | Low-Medium |
| 21 | [Prototype](patterns_tier2.md#21-prototype) | Creational | Clone template objects | Low |
| 22 | [Circuit Breaker](patterns_distributed.md#22-circuit-breaker) | Resilience | Payment/notification/fraud calls | **High (sys design)** |
| 23 | [Retry + Backoff + Jitter](patterns_distributed.md#23-retry-with-exponential-backoff--jitter) | Resilience | External API calls (Stripe, FCM) | **High (sys design)** |
| 24 | [Bulkhead](patterns_distributed.md#24-bulkhead) | Resilience | Isolate payment/fraud/geocode pools | **High (sys design)** |
| 25 | [Saga](patterns_distributed.md#25-saga-pattern-distributed-transactions) | Distributed | Distributed booking transaction | **High (sys design)** |
| 26 | [CQRS](patterns_distributed.md#26-cqrs--command-query-responsibility-segregation) | Data | Ride write DB + read projections | **High (sys design)** |
| 27 | [Event Sourcing](patterns_distributed.md#27-event-sourcing) | Data | Audit log, trip event stream | **High (sys design)** |
| 28 | [Outbox Pattern](patterns_distributed.md#28-outbox-pattern) | Data | Atomic DB write + Kafka publish | **High (sys design)** |
| 29 | [BFF](patterns_distributed.md#29-backend-for-frontend-bff) | API | Mobile/web/partner API layers | **High (sys design)** |
| 30 | [Sidecar](patterns_distributed.md#30-sidecar) | Infrastructure | Envoy proxy, log shipper, metrics | Medium (sys design) |
| 31 | [Strangler Fig](patterns_distributed.md#31-strangler-fig) | Migration | Monolith → microservices | Medium (sys design) |

---

## SOLID Principles

| Principle | Rule | Violated by |
|-----------|------|------------|
| **S**ingle Responsibility | One class = one reason to change | God class handling auth + matching + billing |
| **O**pen/Closed | Open for extension, closed for modification | Switch statements on type (add a new case → modify) |
| **L**iskov Substitution | Subclass can replace superclass without changing behavior | `PoolRide.calculateFare()` that throws for single riders |
| **I**nterface Segregation | Don't force clients to depend on unused methods | `DriverRepository` with 20 methods when most classes use 3 |
| **D**ependency Inversion | Depend on abstractions, not concretions | `new PostgresRideRepository()` hardcoded in `BookingService` |

---

## Quick Decision Guide

**Structuring object creation:**
- One concrete type, varied construction → **Builder**
- Multiple related types, same interface → **Factory Method**
- Families of related types → **Abstract Factory**
- One global instance → **Singleton**
- Clone existing → **Prototype**

**Adding behavior without modifying classes:**
- Wrap with extra behavior, stackable → **Decorator**
- Control access / remote / lazy → **Proxy**
- Simplified entry point to subsystem → **Facade**
- Incompatible interfaces → **Adapter**

**Managing state and flow:**
- Swappable algorithms → **Strategy**
- Notify many on change → **Observer**
- Object behavior varies by state → **State**
- Fixed workflow, variable steps → **Template Method**
- Request as object, undo-able → **Command**
- Sequential handlers, any can stop → **Chain of Responsibility**

**Distributed systems:**
- Dependency may fail → **Circuit Breaker** + **Retry + Jitter**
- Failure isolation between services → **Bulkhead**
- Multi-service transaction → **Saga**
- Read/write at different scales → **CQRS**
- Full audit / time travel → **Event Sourcing**
- Atomic DB + message → **Outbox**
- Client-specific APIs → **BFF**
- Cross-cutting infra per pod → **Sidecar**
- Incremental monolith migration → **Strangler Fig**
