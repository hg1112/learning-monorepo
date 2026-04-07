# Message Queues: Kafka vs RabbitMQ vs SQS vs MQTT vs others

When to use each queue type is one of the most common system design questions. The wrong choice at scale is expensive to fix. This doc gives concrete decision rules, numbers, and the "why" behind each choice.

---

## The Core Question: What Does a Message Queue Actually Do?

Before comparing, understand the fundamental axis: **dumb broker vs smart broker**.

```
DUMB BROKER (Kafka, Kinesis, Pulsar)
  The broker stores messages. Consumers track their own position (offset).
  Broker doesn't know which consumer read what. Replaying is trivial.
  Ordering guaranteed within a partition.
  Throughput: millions of msg/sec. Latency: 5-50ms.

SMART BROKER (RabbitMQ, SQS, ActiveMQ)
  The broker tracks which consumer received each message.
  Once a consumer ACKs, the message is deleted (or moved to DLQ).
  Routing logic lives in the broker (exchanges, routing keys, fanout).
  Throughput: tens of thousands of msg/sec. Latency: 1-5ms.

PROTOCOL-FIRST (MQTT, AMQP)
  Designed for specific network constraints (IoT, low-bandwidth, unreliable links).
  The protocol matters more than the broker implementation.
  Throughput: varies. Latency: sub-millisecond to seconds.
```

---

## Decision Matrix

| Criterion | Kafka | RabbitMQ | AWS SQS | MQTT | Redis Streams |
|-----------|-------|----------|---------|------|---------------|
| Throughput | Millions/sec | ~50K/sec | ~3K/sec (standard), ~30K/sec (FIFO) | Varies (broker-dependent) | ~100K/sec |
| Latency | 5-50ms (batching) | 1-5ms | 100-500ms (standard) | <1ms (local broker) | 1-2ms |
| Message ordering | Per-partition ordered | Per-queue ordered (single consumer) | Not guaranteed (standard), FIFO optional | QoS-dependent | Per-stream ordered |
| Replay / rewind | Yes (log retention, configurable) | No (ACK = delete) | No (visibility timeout, then delete) | No | Yes (XRANGE) |
| Fan-out (1:N consumers) | Native (consumer groups read independently) | Via exchanges (fanout, topic) | Via SNS topic → multiple SQS queues | Via topic subscriptions | Consumer groups |
| Routing logic | Partition key (producer decides) | Exchange + routing key (broker decides) | Simple queue or topic ARN | Topic hierarchy (`home/sensor/temp`) | Consumer groups only |
| Persistence | Yes (disk, configurable retention) | Optional (durable queues) | Yes (SQS manages) | QoS 1/2 on broker | In-memory + AOF |
| Push vs pull | Pull (consumer polls) | Push (broker pushes to consumer) | Pull (long polling) | Push (broker pushes) | Pull (XREAD) |
| Dead-letter queue | Manual (separate topic) | Built-in (x-dead-letter-exchange) | Built-in (DLQ attribute) | No built-in | Manual |
| Exactly-once delivery | Yes (idempotent producers + transactional API) | No (at-least-once) | FIFO queues only | QoS 2 only | No |
| Setup complexity | High (Kafka + ZooKeeper or KRaft) | Medium (broker + optional cluster) | None (managed) | Low (MQTT broker like Mosquitto) | Zero (Redis already running) |
| Cost | Infrastructure cost | Infrastructure cost | Pay per API call | Infrastructure cost | Redis cost |

---

## Kafka — When and Why

### Use Kafka when:
1. **You need replay** — the ability to re-read messages from any point in time
2. **Multiple independent consumers** read the same events (fan-out without duplication)
3. **High throughput** — more than 50K messages/second sustained
4. **Event sourcing** — the log IS the source of truth, not a side effect
5. **Stream processing** — Flink/Spark Streaming consumes the Kafka topic directly
6. **Audit trail** — keep all events for 7 days (or indefinitely with tiered storage)

### Kafka's killer feature: consumer groups + independent offsets

```
Topic: order-events (6 partitions)

Partition 0: [msg1, msg2, msg3, msg4, ...]
Partition 1: [msg5, msg6, msg7, msg8, ...]
...

Consumer Group A (Notification Worker): reads from offset 100
Consumer Group B (ML Platform labels):  reads from offset 100 independently
Consumer Group C (Analytics):           reads from offset 1 (replaying history)

All three groups read the SAME events. The broker never deletes the message
after one consumer reads it. Retention (7 days by default) handles deletion.
```

**RabbitMQ can't do this**: once a consumer ACKs a message, it's gone. The ML Platform would need a separate copy of the same data delivered to a separate queue. You'd have to publish to multiple queues — duplicating data and coupling producers to consumer count.

### Kafka's weak spots:
- **Latency**: Kafka batches messages for throughput. Default `linger.ms=0` but network overhead + batch flushing → P99 latency is 50-100ms, not 1ms. RabbitMQ delivers in 1-5ms.
- **No routing logic**: You can't tell Kafka "send this to consumers who subscribed to Italian restaurants only". Partition key is the only routing primitive. Filtering happens in the consumer.
- **Operational complexity**: KRaft (our docker-compose setup) simplifies it, but Kafka still needs careful partition + replication configuration.
- **Not great for work queues**: If you have 100 workers and want Kafka to distribute tasks evenly, you need at least 100 partitions (one per worker). RabbitMQ handles this naturally with competing consumers on a single queue.

### Kafka in our Uber implementation:
```
order-events       → Notification Worker (push notifications)
                   → (future) Analytics pipeline
ad-impression-events → Notification Worker
                     → ML Platform (training labels)   ← TWO independent consumers
ad-click-events    → ML Platform (training labels)
ad-created-events  → ML Platform (embedding trigger)
location-events    → Analytics
ad-write-behind    → Campaign Mgmt (async DB flush)
```
The `ad-impression-events` topic has two consumer groups reading it independently. This would require two separate queues in RabbitMQ, coupling the Campaign Mgmt service to the consumer count.

---

## RabbitMQ — When and Why

### Use RabbitMQ when:
1. **Low latency matters** — you need <5ms delivery, not 50ms
2. **Complex routing** — "send to any consumer that subscribed to topic X.Y.*"
3. **Work queues with competing consumers** — N workers pull from one queue; each message goes to exactly one worker
4. **Per-message TTL and DLQ** — each message can have its own expiry, with a built-in dead-letter path
5. **Request-reply pattern** — producer sends a task, waits for a result (RPC over MQ)
6. **Existing AMQP ecosystem** — your language/framework has a great AMQP client

### RabbitMQ's exchange model (the routing superpower):

```
DIRECT exchange: routing_key == binding_key
  Producer sends to exchange "payments" with routing_key="credit_card"
  → only queues bound with key "credit_card" receive it
  Use case: route by event type

FANOUT exchange: broadcast to all bound queues
  Producer sends one message
  → ALL bound queues receive a copy
  Use case: cache invalidation broadcast (1 event → 10 app servers clear cache)

TOPIC exchange: wildcard routing
  Producer sends with routing_key="eats.order.placed"
  Queue A bound with "eats.#" → receives it
  Queue B bound with "*.order.*" → receives it
  Queue C bound with "rides.#" → does NOT receive it
  Use case: multi-tenant event routing, fine-grained subscriptions

HEADERS exchange: route by message headers (not routing key)
  Producer sends with headers {region: "EU", type: "premium"}
  Queue bound with {region: "EU"} → receives it
  Use case: attribute-based routing (rare, usually overkill)
```

### RabbitMQ's weak spots:
- **No replay**: Once consumed and ACKed, the message is gone. You cannot re-read the last 7 days of events for a new consumer or for debugging.
- **Throughput ceiling**: ~50K messages/sec per node. This is enough for most applications but not for location events (200K/sec) or impression tracking (100K/sec).
- **Queue length = performance**: A queue with millions of unprocessed messages (slow consumer) causes RabbitMQ memory pressure and performance degradation. Kafka's log structure handles unbounded backlogs gracefully.

### When RabbitMQ beats Kafka in practice:
- **Email/notification dispatch**: Each email goes to exactly one worker. No replay needed. Need per-message TTL (don't send a "flash sale" email 2 hours late). RabbitMQ's message TTL + DLQ handles this cleanly.
- **Task queues for background jobs**: Image resizing, PDF generation, report export. One task = one worker. Competing consumers distribute load automatically. No reason to keep processed tasks.
- **RPC over message queue**: Service A sends a task with a `reply_to` queue. Service B processes it and sends the result back to that queue. Clean request-reply. Kafka requires awkward correlation ID + result topic pattern.

---

## AWS SQS — When and Why

### Use SQS when:
1. **You're all-in on AWS** and want zero ops overhead
2. **Throughput is moderate** (<30K msg/sec for FIFO, unlimited for standard with duplication risk)
3. **Decoupling without infrastructure** — pay per API call, no servers to manage
4. **Simple work queue** — one producer, one consumer pool, no replay, no fan-out
5. **Visibility timeout is sufficient** for your retry pattern

### SQS standard vs FIFO:
```
STANDARD queue:
  ✓ Virtually unlimited throughput
  ✗ At-least-once delivery (rare duplicates possible)
  ✗ Best-effort ordering (not guaranteed)
  Use: task queues where ordering doesn't matter and idempotency is easy

FIFO queue:
  ✓ Exactly-once processing (deduplication ID)
  ✓ Ordered within a message group
  ✗ 3,000 msg/sec limit (with batching), 300/sec without
  ✗ More expensive
  Use: financial transactions, ordered workflows
```

### SQS weak spots:
- **Visibility timeout trap**: Instead of ACK/NACK, SQS makes a message "invisible" for a timeout. If processing exceeds timeout (e.g., image resize takes longer than expected), the message becomes visible again and another worker picks it up — duplicate processing.
- **No replay**: Like RabbitMQ, once consumed and deleted, gone forever.
- **Latency**: SQS standard has 100-500ms latency (intentional — AWS optimizes for durability and throughput, not latency). Not suitable for real-time use cases.
- **Vendor lock-in**: SQS API doesn't translate to anything else. Migrating off SQS means rewriting producers and consumers.

### SQS + SNS fan-out pattern:
```
SNS topic "order-placed"
  → SQS queue "notifications"  (Notification Worker subscribes)
  → SQS queue "analytics"      (Analytics service subscribes)
  → SQS queue "fraud-check"    (Fraud service subscribes)

This mimics Kafka's consumer group pattern but:
  - Requires N SQS queues for N consumers (each consumer gets its own queue)
  - No replay (SQS deletes after consumption)
  - Producer must publish to SNS, not SQS directly
  - Every consumer gets the message independently (fan-out via SNS)
```
This pattern works but becomes expensive at high volume (SNS + SQS = double API calls). Kafka is cheaper and simpler at scale.

---

## MQTT — When and Why

MQTT is a **protocol**, not a specific broker. The broker (Mosquitto, HiveMQ, EMQX) implements MQTT. It was designed in 1999 for monitoring oil pipelines via satellite — extremely low bandwidth, unreliable connections.

### Use MQTT when:
1. **IoT / embedded devices** — sensors, drivers' mobile apps on cellular
2. **Unreliable networks** — the protocol handles disconnects gracefully (persistent sessions)
3. **Very low bandwidth** — MQTT packet overhead is 2 bytes fixed header vs HTTP's ~800 bytes minimum
4. **Push semantics** — broker pushes to subscribers, clients don't poll
5. **QoS control per message** — fire-and-forget vs at-least-once vs exactly-once

### MQTT QoS levels:
```
QoS 0: Fire and forget (at-most-once)
  No ACK. Message may be lost if network drops.
  Overhead: zero retransmission.
  Use: sensor readings where the next update arrives in 5s anyway.

QoS 1: At-least-once
  Publisher retries until broker ACKs. Consumer may receive duplicates.
  Overhead: 1 ACK round trip.
  Use: driver location updates (duplicates are harmless — idempotent GEOADD).

QoS 2: Exactly-once
  4-way handshake (PUBLISH → PUBREC → PUBREL → PUBCOMP).
  Overhead: 2 round trips per message.
  Use: payment commands, order placement (cannot process twice).
```

### MQTT vs gRPC streaming for driver location:
```
MQTT:
  ✓ Designed for unreliable cellular — persistent session survives disconnects
  ✓ Built-in QoS levels (fire-and-forget for location, exactly-once for commands)
  ✓ Push model — broker pushes to driver app (RideAssignment event)
  ✓ Tiny packet size (2-byte overhead)
  ✗ No schema enforcement (raw bytes)
  ✗ Limited tooling for server-side streaming analytics

gRPC bidirectional stream (our implementation):
  ✓ Schema enforced via protobuf (type-safe)
  ✓ HTTP/2 flow control + backpressure
  ✓ First-class Spring Boot support (@GrpcService)
  ✓ HPACK header compression
  ✗ More brittle on mobile networks (HTTP/2 requires stable TCP)
  ✗ Requires native SDK (no browser support without grpc-web)

Uber's actual choice: MQTT for driver location (optimized for mobile network
reliability). gRPC for service-to-service (stable datacenter networks,
schema enforcement matters).

Our implementation uses gRPC because: simpler to set up locally, same
conceptual pattern (persistent stream), and MQTT would require a separate
MQTT broker and Spring MQTT integration.
```

### MQTT in the Uber architecture (SOTA):
```
Driver App  →[MQTT QoS 1]→  MQTT Broker (HiveMQ/EMQX)
                                    ↓
                             Kafka Connector (MQTT source connector)
                                    ↓
                           Kafka: location-events topic
                                    ↓
                 ┌──────────────────┴──────────────────┐
         Location Service                      Analytics Pipeline
         (Redis GEOADD)                        (Flink → ClickHouse)
```
MQTT handles the unreliable mobile layer. Once the message reaches the MQTT broker (datacenter), it bridges to Kafka for internal distribution. This is the standard IoT ingest pattern.

---

## Redis Streams — When and Why

Redis Streams (`XADD`, `XREAD`, `XACK`) are a Kafka-lite message queue built into Redis.

### Use Redis Streams when:
1. **You already have Redis** and want lightweight pub/sub without another service
2. **Low volume** — thousands, not millions, of messages/sec
3. **Consumer groups with ACK semantics** — like Kafka but simpler
4. **Message retention without full Kafka overhead**

### Redis Streams vs Kafka:
| Property | Redis Streams | Kafka |
|----------|--------------|-------|
| Setup | Zero (already have Redis) | Separate service |
| Throughput | ~100K msg/sec | Millions/sec |
| Persistence | Optional (AOF/RDB) | Yes (log segments) |
| Retention | Manual trimming (`MAXLEN`) | Time + size based |
| Replay | Yes (`XRANGE` from any ID) | Yes |
| Consumer groups | Yes (`XGROUP`) | Yes |
| At-least-once | Yes (XACK pattern) | Yes |
| Monitoring | Redis CLI | Kafka UI, Cruise Control, etc. |
| Best for | Internal service events, low-volume queues | High-volume, critical event backbone |

**Concrete Redis Streams use case**: the `ad-write-behind` topic in our implementation could have been a Redis Stream instead of a Kafka topic. Volume is low (ad updates, not impressions). We already have Redis. But we used Kafka for consistency — all async communication uses Kafka, reducing operational surface area.

---

## Summary: Decision Rules

```
START HERE:
  Is this IoT / mobile with unreliable networks?  → MQTT
  Is this internal service-to-service?            → continue below

  Do you need replay (re-read old events)?
    Yes → Kafka or Kinesis (AWS)
    No  → continue below

  Do you need fan-out (multiple independent consumers)?
    Yes → Kafka (consumer groups) or SNS+SQS (AWS)
    No  → continue below

  Do you need complex routing (topic patterns, attribute filters)?
    Yes → RabbitMQ (topic/headers exchange)
    No  → continue below

  Do you need <5ms latency?
    Yes → RabbitMQ or Redis Streams
    No  → continue below

  Are you all-in on AWS with low-to-moderate volume?
    Yes → SQS (zero ops overhead)
    No  → RabbitMQ (work queues) or Kafka (high volume)

  Do you already have Redis and volume is low?
    Yes → Redis Streams (zero new infrastructure)
```

### Applied to Uber services:

| Event | Chosen | Why NOT the alternatives |
|-------|--------|--------------------------|
| Driver location (raw) | MQTT → Kafka | MQTT: handles cellular. Kafka: replay for analytics, fan-out to Location + Analytics consumers |
| Order placed | Kafka | Fan-out: Notification Worker + future Flink analytics read independently |
| Ad impressions | Kafka | Fan-out: Notification Worker + ML Platform (training labels). 100K/sec throughput |
| Ad created | Kafka | Replay: if ML Platform is down, catch up on 7 days of new ads when it restarts |
| Ad write-behind | Kafka | Consistency: same broker as everything else. Redis Streams would also work here |
| Ride booking | Kafka | Replay: audit trail of every booking state transition |
| Budget alerts | Kafka (from Flink) | Fan-out: all Ad Server instances consume BudgetExhausted events |
| Email dispatch | RabbitMQ (hypothetical) | No replay needed. Per-message TTL (don't deliver flash sale email late). Work queue semantics (one worker per email) |
| Image resize jobs | SQS (hypothetical) | Work queue, AWS-native, zero ops. At-most 1000 resizes/sec |
