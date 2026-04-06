# Phase 4 — Notification Worker (Port 8084)
# Phase 5 — API Gateway (Port 8080)

---

# Phase 4 — Notification Worker

Pure Kafka consumer — no web layer, no database. Listens to events from all other services and acts on them (logging, push notifications, email, etc.).

**Why a separate service?** The notification concern is decoupled from the business logic. Eats Service doesn't care *how* the restaurant is notified — it only publishes an event. The Notification Worker can be updated, restarted, or scaled independently. Kafka's consumer group offset means zero events are lost during a worker restart.

---

## Directory Structure

```
apps/uber/notification-worker/
├── BUILD.bazel
└── src/main/java/com/uber/notification/
    ├── NotificationWorkerApplication.java
    ├── KafkaConsumerConfig.java
    ├── OrderEventConsumer.java
    ├── RideEventConsumer.java
    └── AdImpressionConsumer.java
    resources/application.properties
```

---

## File 1: BUILD.bazel

```python
# apps/uber/notification-worker/BUILD.bazel
load("@rules_java//java:defs.bzl", "java_binary", "java_library")

java_library(
    name = "notification_lib",
    srcs = glob(["src/main/java/**/*.java"]),
    resources = glob(["src/main/resources/**"]),
    deps = [
        # No spring-boot-starter-web — this service has no HTTP endpoints.
        # Only needs Kafka consumer and Spring Boot core.
        "@maven//:org_springframework_kafka_spring_kafka",
        "@maven//:com_fasterxml_jackson_core_jackson_databind",
    ],
)

java_binary(
    name = "notification-worker",
    main_class = "com.uber.notification.NotificationWorkerApplication",
    runtime_deps = [":notification_lib"],
)
```

---

## File 2: application.properties

```properties
# apps/uber/notification-worker/src/main/resources/application.properties
server.port=8084
spring.application.name=notification-worker

# Disable web server — this service only runs Kafka consumers, no HTTP.
# Without this, Spring Boot starts Tomcat but serves nothing, wasting resources.
spring.main.web-application-type=none

# Kafka consumer config
spring.kafka.bootstrap-servers=localhost:9092

# Consumer group — Kafka tracks this group's offset per topic+partition.
# If the worker restarts, it resumes from the last committed offset (no lost events).
spring.kafka.consumer.group-id=notification-workers

# earliest: on first startup (no committed offset), read from the beginning.
# latest: on first startup, skip historical messages (use for live systems).
spring.kafka.consumer.auto-offset-reset=earliest

# Deserialize message keys and values as strings.
spring.kafka.consumer.key-deserializer=org.apache.kafka.common.serialization.StringDeserializer
spring.kafka.consumer.value-deserializer=org.apache.kafka.common.serialization.StringDeserializer

logging.level.com.uber.notification=INFO
```

---

## File 3: NotificationWorkerApplication.java

```java
// apps/uber/notification-worker/src/main/java/com/uber/notification/NotificationWorkerApplication.java
package com.uber.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class NotificationWorkerApplication {
    public static void main(String[] args) {
        SpringApplication.run(NotificationWorkerApplication.class, args);
    }
}
```

---

## File 4: KafkaConsumerConfig.java

```java
// apps/uber/notification-worker/src/main/java/com/uber/notification/KafkaConsumerConfig.java
package com.uber.notification;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;

import java.util.HashMap;
import java.util.Map;

// @EnableKafka activates @KafkaListener annotations on @Component/@Service beans.
@EnableKafka
@Configuration
public class KafkaConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id}")
    private String groupId;

    @Bean
    public ConsumerFactory<String, String> consumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        // Manual offset commit would give us exactly-once semantics.
        // For notifications (idempotent), auto-commit is fine.
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, true);
        props.put(ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG, 1000);
        return new DefaultKafkaConsumerFactory<>(props);
    }

    // The container factory controls concurrency (number of consumer threads).
    // Each thread handles one Kafka partition. Set concurrency = number of partitions.
    // Default: 1 thread. For production, set to match partition count.
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());
        factory.setConcurrency(1); // increase to match Kafka partition count
        return factory;
    }
}
```

---

## File 5: OrderEventConsumer.java

```java
// apps/uber/notification-worker/src/main/java/com/uber/notification/OrderEventConsumer.java
package com.uber.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

// Consumes "order-events" published by Eats Service.
//
// In production this would:
//   - Send push notification to restaurant app (FCM/APNs)
//   - Send SMS to customer ("Your order is being prepared")
//   - Trigger an email receipt
//
// Here it logs the event, demonstrating the decoupling pattern.
// The Eats Service returns 201 immediately without knowing or caring
// whether this consumer is running.
@Component
public class OrderEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderEventConsumer.class);

    @KafkaListener(
        topics = "order-events",
        groupId = "notification-workers",
        containerFactory = "kafkaListenerContainerFactory"
    )
    public void handleOrderEvent(String message) {
        log.info("[ORDER EVENT] Received: {}", message);
        // Parse status from the JSON event and act accordingly.
        // Example: if status=PREPARING → notify restaurant
        //          if status=OUT_FOR_DELIVERY → notify customer
        //          if status=DELIVERED → trigger billing + review prompt
        //
        // Real implementation would use Jackson ObjectMapper to parse
        // the JSON and a NotificationService to send push/SMS/email.
        if (message.contains("PREPARING")) {
            log.info("[ORDER EVENT] Notifying restaurant of new order");
        } else if (message.contains("OUT_FOR_DELIVERY")) {
            log.info("[ORDER EVENT] Notifying customer: order is on the way");
        } else if (message.contains("DELIVERED")) {
            log.info("[ORDER EVENT] Order delivered — triggering review prompt");
        }
    }
}
```

---

## File 6: RideEventConsumer.java

```java
// apps/uber/notification-worker/src/main/java/com/uber/notification/RideEventConsumer.java
package com.uber.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class RideEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(RideEventConsumer.class);

    @KafkaListener(
        topics = "ride-events",
        groupId = "notification-workers",
        containerFactory = "kafkaListenerContainerFactory"
    )
    public void handleRideEvent(String message) {
        log.info("[RIDE EVENT] Received: {}", message);
        if (message.contains("MATCHED")) {
            log.info("[RIDE EVENT] Driver matched — sending driver details to rider");
        } else if (message.contains("IN_PROGRESS")) {
            log.info("[RIDE EVENT] Ride started — starting live tracking updates");
        } else if (message.contains("COMPLETED")) {
            log.info("[RIDE EVENT] Ride completed — initiating payment and receipt");
        } else if (message.contains("CANCELLED")) {
            log.info("[RIDE EVENT] Ride cancelled — notifying rider and freeing driver");
        }
    }
}
```

---

## File 7: AdImpressionConsumer.java

```java
// apps/uber/notification-worker/src/main/java/com/uber/notification/AdImpressionConsumer.java
package com.uber.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

// Consumes ad impression events.
//
// In production, a separate Apache Flink job would also consume "ad-impression-events"
// to perform real-time budget aggregation (1-second tumbling windows).
// When a budget hits zero, Flink emits a BudgetExhausted event, and
// all Ad Server instances immediately remove the ad from their L1 cache.
//
// Here the Notification Worker simply logs — demonstrates multiple independent
// consumers on the same Kafka topic (different group IDs = independent offsets).
@Component
public class AdImpressionConsumer {

    private static final Logger log = LoggerFactory.getLogger(AdImpressionConsumer.class);

    @KafkaListener(
        topics = "ad-impression-events",
        groupId = "notification-workers",
        containerFactory = "kafkaListenerContainerFactory"
    )
    public void handleImpressionEvent(String message) {
        log.info("[AD IMPRESSION] Received: {}", message);
        // In production: forward to analytics pipeline (ClickHouse / BigQuery)
        // for campaign performance reporting.
    }
}
```

---

## Verification (Notification Worker)

```bash
bazel build //apps/uber/notification-worker:notification-worker
./bazel-bin/apps/uber/notification-worker/notification-worker

# With Eats and Rides services already running, place an order or request a ride.
# The notification worker logs should show the consumed events in real time.

# Watch the logs live:
# [ORDER EVENT] Received: {"orderId":"...","customerId":"...","status":"PREPARING","total":12.99}
# [ORDER EVENT] Notifying restaurant of new order
```

---
---

# Phase 5 — API Gateway (Port 8080)

All external traffic enters through the gateway. It handles two concerns:
1. **Routing** — proxy requests to the correct microservice based on the URL path.
2. **Rate Limiting** — enforce per-user request limits using Redis.

**Tech**: Spring Cloud Gateway (reactive, Netty-based). Redis for distributed rate limit counters.

**Important**: Spring Cloud Gateway uses the reactive stack (WebFlux + Netty). You MUST NOT add `spring-boot-starter-web` (Tomcat) to this service — they conflict. The gateway BUILD.bazel only references `spring-cloud-starter-gateway`.

---

## Directory Structure

```
apps/uber/api-gateway/
├── BUILD.bazel
└── src/main/java/com/uber/gateway/
    ├── ApiGatewayApplication.java
    ├── GatewayRouteConfig.java
    ├── RedisConfig.java
    ├── RateLimiterStrategy.java    ← interface
    ├── TokenBucketRateLimiter.java ← strategy 1
    ├── SlidingWindowRateLimiter.java ← strategy 2
    └── RateLimiterFilter.java
    resources/application.properties
```

---

## File 1: BUILD.bazel

```python
# apps/uber/api-gateway/BUILD.bazel
load("@rules_java//java:defs.bzl", "java_binary", "java_library")

java_library(
    name = "gateway_lib",
    srcs = glob(["src/main/java/**/*.java"]),
    resources = glob(["src/main/resources/**"]),
    deps = [
        # spring-cloud-starter-gateway INCLUDES spring-boot-starter-webflux
        # and Netty. Do NOT add spring-boot-starter-web here.
        "@maven//:org_springframework_cloud_spring_cloud_starter_gateway",
        "@maven//:org_springframework_boot_spring_boot_starter_data_redis",
    ],
)

java_binary(
    name = "api-gateway",
    main_class = "com.uber.gateway.ApiGatewayApplication",
    runtime_deps = [":gateway_lib"],
)
```

---

## File 2: application.properties

```properties
# apps/uber/api-gateway/src/main/resources/application.properties
server.port=8080
spring.application.name=api-gateway

# Redis — stores rate limit counters shared across all gateway instances.
# If you run 3 gateway instances behind a load balancer, they all share
# these counters — one user can't bypass limits by hitting different instances.
spring.data.redis.host=localhost
spring.data.redis.port=6379

# Rate limiter algorithm — change to switch:
#   token-bucket     (default) allows bursts, memory-efficient
#   sliding-window   exact accuracy, higher Redis memory usage
gateway.ratelimit.algorithm=token-bucket

# Token Bucket config: 60 requests per minute, burst up to 60.
# A user idle for 60s has a full bucket → can make 60 requests instantly.
# This is correct behavior for a human opening the app (many parallel API calls).
gateway.ratelimit.capacity=60
gateway.ratelimit.refill-rate=1

# Sliding Window config: 60 requests in any rolling 60-second window.
gateway.ratelimit.window-seconds=60
gateway.ratelimit.window-limit=60

logging.level.com.uber.gateway=DEBUG
logging.level.org.springframework.cloud.gateway=INFO
```

---

## File 3: ApiGatewayApplication.java

```java
// apps/uber/api-gateway/src/main/java/com/uber/gateway/ApiGatewayApplication.java
package com.uber.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ApiGatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}
```

---

## File 4: GatewayRouteConfig.java

```java
// apps/uber/api-gateway/src/main/java/com/uber/gateway/GatewayRouteConfig.java
package com.uber.gateway;

// Spring Cloud Gateway routes: match incoming request path → proxy to service.
//
// How it works:
//   1. Request arrives at :8080
//   2. RateLimiterFilter runs (GlobalFilter — applies to ALL routes)
//   3. If allowed, RouteLocator matches the path and proxies to the backend
//   4. Response is streamed back to the client
//
// The gateway is transparent — it forwards all headers and bodies unchanged.
// The only modification is adding X-Gateway-Request-Id for tracing.

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GatewayRouteConfig {

    @Bean
    public RouteLocator routes(RouteLocatorBuilder builder) {
        return builder.routes()

            // Eats Service: /api/eats/** → http://localhost:8081
            .route("eats-service", r -> r
                .path("/api/eats/**")
                .filters(f -> f.addRequestHeader("X-Gateway-Source", "api-gateway"))
                .uri("http://localhost:8081"))

            // Ads Service: /api/ads/** → http://localhost:8082
            .route("ads-service", r -> r
                .path("/api/ads/**")
                .filters(f -> f.addRequestHeader("X-Gateway-Source", "api-gateway"))
                .uri("http://localhost:8082"))

            // Rides Service: /api/rides/** → http://localhost:8083
            .route("rides-service", r -> r
                .path("/api/rides/**")
                .filters(f -> f.addRequestHeader("X-Gateway-Source", "api-gateway"))
                .uri("http://localhost:8083"))

            .build();
    }
}
```

---

## File 5: RedisConfig.java

```java
// apps/uber/api-gateway/src/main/java/com/uber/gateway/RedisConfig.java
package com.uber.gateway;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

// The gateway is reactive (WebFlux). We use ReactiveStringRedisTemplate
// to avoid blocking the event loop. Blocking Redis calls in a reactive
// pipeline would cause thread starvation under load.
@Configuration
public class RedisConfig {

    @Bean
    public ReactiveStringRedisTemplate reactiveRedisTemplate(
            ReactiveRedisConnectionFactory factory) {
        return new ReactiveStringRedisTemplate(factory);
    }
}
```

---

## File 6: RateLimiterStrategy.java (interface)

```java
// apps/uber/api-gateway/src/main/java/com/uber/gateway/RateLimiterStrategy.java
package com.uber.gateway;

import reactor.core.publisher.Mono;

// Reactive interface — returns Mono<Boolean> instead of blocking boolean.
// This keeps the gateway's request pipeline fully non-blocking.
public interface RateLimiterStrategy {
    Mono<Boolean> isAllowed(String userId);
}
```

---

## File 7: TokenBucketRateLimiter.java

```java
// apps/uber/api-gateway/src/main/java/com/uber/gateway/TokenBucketRateLimiter.java
package com.uber.gateway;

// Token Bucket algorithm using a Redis Lua script for atomicity.
//
// Why Lua?
// The algorithm requires: read tokens → calculate refill → write tokens.
// That's 3 operations. Without Lua, two concurrent requests could both read
// "5 tokens remaining" and both get allowed, racing to decrement to 4.
// Redis executes Lua scripts atomically — the entire script is a single
// command from Redis's perspective. No race conditions possible.
//
// Script logic:
//   1. Read last_tokens and last_refill_ts (or use defaults on first call)
//   2. elapsed = now - last_refill_ts
//   3. new_tokens = min(capacity, last_tokens + elapsed * rate)
//   4. if new_tokens >= 1: allowed=true, new_tokens -= 1
//   5. Write new_tokens and now back to Redis
//   6. Return {allowed, remaining_tokens}
//
// Token Bucket vs Fixed Window:
//   Fixed Window: "10 req per minute". At 11:59:59 → 10 requests. At 12:00:01 → 10 more.
//   20 requests in 2 seconds — defeats the purpose.
//   Token Bucket: refills continuously. No boundary spike possible.
//
// Token Bucket vs Sliding Window:
//   Sliding Window: exact accuracy, high memory (sorted set of timestamps).
//   Token Bucket: allows bursts (good for humans), low memory (2 Redis keys per user).

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

@Component
@ConditionalOnProperty(name = "gateway.ratelimit.algorithm", havingValue = "token-bucket", matchIfMissing = true)
public class TokenBucketRateLimiter implements RateLimiterStrategy {

    private final ReactiveStringRedisTemplate redisTemplate;

    @Value("${gateway.ratelimit.capacity:60}")
    private long capacity;

    @Value("${gateway.ratelimit.refill-rate:1}")
    private long refillRate; // tokens per second

    // Lua script stored as a constant. Redis compiles it once on first EVAL call.
    private static final String LUA_SCRIPT =
        "local tokens_key = KEYS[1] .. ':tokens'\n" +
        "local ts_key     = KEYS[1] .. ':ts'\n" +
        "local capacity   = tonumber(ARGV[1])\n" +
        "local rate       = tonumber(ARGV[2])\n" +
        "local now        = tonumber(ARGV[3])\n" +
        "local requested  = 1\n" +
        // Fill time = how long a full bucket takes to empty
        "local fill_time  = capacity / rate\n" +
        "local ttl        = math.floor(fill_time * 2)\n" +
        // Read current state (nil on first call → use defaults)
        "local last_tokens    = tonumber(redis.call('GET', tokens_key))\n" +
        "local last_refreshed = tonumber(redis.call('GET', ts_key))\n" +
        "if last_tokens    == nil then last_tokens    = capacity end\n" +
        "if last_refreshed == nil then last_refreshed = now      end\n" +
        // Calculate token refill based on elapsed time
        "local elapsed     = math.max(0, now - last_refreshed)\n" +
        "local new_tokens  = math.min(capacity, last_tokens + elapsed * rate)\n" +
        // Decide and deduct
        "local allowed = new_tokens >= requested\n" +
        "if allowed then new_tokens = new_tokens - requested end\n" +
        // Persist with TTL (auto-cleanup for inactive users)
        "redis.call('SETEX', tokens_key, ttl, new_tokens)\n" +
        "redis.call('SETEX', ts_key,     ttl, now)\n" +
        "if allowed then return 1 else return 0 end\n";

    private final RedisScript<Long> script = RedisScript.of(LUA_SCRIPT, Long.class);

    public TokenBucketRateLimiter(ReactiveStringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Mono<Boolean> isAllowed(String userId) {
        String key = "ratelimit:token:" + userId;
        long nowSeconds = System.currentTimeMillis() / 1000;

        List<String> keys = List.of(key);
        List<String> args = Arrays.asList(
                String.valueOf(capacity),
                String.valueOf(refillRate),
                String.valueOf(nowSeconds));

        return redisTemplate.execute(script, keys, args)
                .next()
                .map(result -> result == 1L)
                .defaultIfEmpty(true); // fail open: if Redis is down, allow requests
    }
}
```

---

## File 8: SlidingWindowRateLimiter.java

```java
// apps/uber/api-gateway/src/main/java/com/uber/gateway/SlidingWindowRateLimiter.java
package com.uber.gateway;

// Sliding Window Log algorithm using Redis sorted sets.
//
// Data structure: ZADD ratelimit:sliding:{userId} <timestamp_ms> <timestamp_ms>
//   Score = timestamp (milliseconds). Member = timestamp (unique per request).
//
// On each request:
//   1. ZREMRANGEBYSCORE key 0 (now - windowMs)  — remove expired entries
//   2. ZCARD key                                 — count entries in window
//   3. if count < limit: ZADD key now now        — allow and record
//                        EXPIRE key window*2      — auto-cleanup
//   4. else: return false (429)
//
// Exact accuracy: at every millisecond, the window contains exactly the
// requests from the past 60 seconds. No boundary spike is possible.
//
// Memory cost: each request stores one sorted set entry (~100 bytes).
// At 1M active users × 60 req/min = 60M entries ≈ 6GB Redis memory.
// Token Bucket uses only 2 string keys per user ≈ 200 bytes.

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;

@Component
@ConditionalOnProperty(name = "gateway.ratelimit.algorithm", havingValue = "sliding-window")
public class SlidingWindowRateLimiter implements RateLimiterStrategy {

    private final ReactiveStringRedisTemplate redisTemplate;

    @Value("${gateway.ratelimit.window-seconds:60}")
    private long windowSeconds;

    @Value("${gateway.ratelimit.window-limit:60}")
    private long limit;

    public SlidingWindowRateLimiter(ReactiveStringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Mono<Boolean> isAllowed(String userId) {
        String key = "ratelimit:sliding:" + userId;
        long now = System.currentTimeMillis();
        long windowStart = now - (windowSeconds * 1000);

        return redisTemplate.opsForZSet()
                // Step 1: remove expired entries (older than window start)
                .removeRangeByScore(key, 0, windowStart)
                .then(redisTemplate.opsForZSet().size(key))
                .flatMap(count -> {
                    if (count >= limit) {
                        return Mono.just(false); // 429
                    }
                    // Step 3: add current timestamp as both score and member
                    // (member must be unique; using timestamp_ms is sufficient
                    //  for our rate: max 1 entry per millisecond per user)
                    String member = String.valueOf(now);
                    return redisTemplate.opsForZSet()
                            .add(key, member, now)
                            .then(redisTemplate.expire(key, Duration.ofSeconds(windowSeconds * 2)))
                            .thenReturn(true);
                })
                .defaultIfEmpty(true); // fail open on Redis error
    }
}
```

---

## File 9: RateLimiterFilter.java

```java
// apps/uber/api-gateway/src/main/java/com/uber/gateway/RateLimiterFilter.java
package com.uber.gateway;

// GlobalFilter: runs for every request through the gateway, before routing.
//
// Request flow:
//   1. Extract X-User-Id header (set by client auth middleware in production)
//   2. Call RateLimiterStrategy.isAllowed(userId) — reactive, non-blocking
//   3. If allowed: continue to routing (chain.filter)
//   4. If denied: return HTTP 429 with Retry-After header
//
// The Ordered interface controls filter execution order.
// getOrder() = -1 means this runs BEFORE Spring Cloud Gateway's built-in filters.
// Use getOrder() = Integer.MIN_VALUE to run before everything.

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class RateLimiterFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RateLimiterFilter.class);

    @Autowired
    private RateLimiterStrategy rateLimiter;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // In production, X-User-Id would be set by a JWT auth filter upstream.
        // For this learning project, the client passes it directly.
        String userId = exchange.getRequest().getHeaders().getFirst("X-User-Id");
        if (userId == null || userId.isBlank()) {
            userId = "anonymous";
        }

        final String uid = userId;

        return rateLimiter.isAllowed(uid)
                .flatMap(allowed -> {
                    if (!allowed) {
                        log.warn("[RATE LIMIT] User {} exceeded limit on {}",
                                uid, exchange.getRequest().getPath());
                        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
                        // Retry-After: tell the client when it can retry (1 second for token bucket)
                        exchange.getResponse().getHeaders()
                                .add(HttpHeaders.RETRY_AFTER, "1");
                        exchange.getResponse().getHeaders()
                                .add("X-Rate-Limit-Exceeded-For", uid);
                        return exchange.getResponse().setComplete();
                    }
                    return chain.filter(exchange);
                });
    }

    @Override
    public int getOrder() {
        return -1; // run before routing filters
    }
}
```

---

## Verification (API Gateway)

```bash
bazel build //apps/uber/api-gateway:api-gateway
./bazel-bin/apps/uber/api-gateway/api-gateway

# ── Test routing ───────────────────────────────────────────────────────────
# All requests now go through :8080 instead of the individual service ports.

# Route to Eats Service
curl -H "X-User-Id: user-123" \
  http://localhost:8080/api/eats/restaurants

# Route to Ads Service (auction)
curl -H "X-User-Id: user-123" \
  "http://localhost:8080/api/ads/serve?cuisine=Italian"

# Route to Rides Service
curl -H "X-User-Id: user-123" \
  "http://localhost:8080/api/rides/drivers/nearby?longitude=-73.985&latitude=40.758"

# ── Test rate limiting ─────────────────────────────────────────────────────
# Send 65 rapid requests — first 60 should succeed, rest get 429
for i in {1..65}; do
  STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
    -H "X-User-Id: test-user" \
    http://localhost:8080/api/eats/restaurants)
  echo "Request $i: $STATUS"
done

# ── Test that different users have independent limits ─────────────────────
# user-A can still make requests while user-B is throttled
curl -H "X-User-Id: user-A" http://localhost:8080/api/eats/restaurants  # 200
curl -H "X-User-Id: user-B" http://localhost:8080/api/eats/restaurants  # 200

# ── Inspect rate limit keys in Redis ──────────────────────────────────────
docker exec -it uber-redis redis-cli
> KEYS ratelimit:*
> GET ratelimit:token:test-user:tokens
> GET ratelimit:token:test-user:ts

# ── Switch to sliding window, rebuild, and compare behavior ───────────────
# In application.properties: gateway.ratelimit.algorithm=sliding-window
# Rebuild and restart. Now observe:
#   - Token bucket: idle user can burst 60 requests immediately
#   - Sliding window: always exactly 60 requests allowed in any rolling 60s window
docker exec -it uber-redis redis-cli
> ZRANGE ratelimit:sliding:test-user 0 -1 WITHSCORES
```

---

## Full System Test

With all five services running, test a complete flow through the gateway:

```bash
# 1. Create a restaurant (Eats)
curl -X POST -H "X-User-Id: admin" -H "Content-Type: application/json" \
  http://localhost:8080/api/eats/restaurants \
  -d '{"name":"Pizza Roma","cuisine":"Italian","address":"1 Main St","menu":[{"name":"Margherita","price":12.99,"category":"Pizza"}]}'

# 2. Register a driver (Rides)
curl -X POST -H "X-User-Id: admin" -H "Content-Type: application/json" \
  http://localhost:8080/api/rides/drivers \
  -d '{"name":"Carlos","vehiclePlate":"UBR-001"}'

# 3. Driver pings location
curl -X PUT -H "X-User-Id: driver-1" -H "Content-Type: application/json" \
  http://localhost:8080/api/rides/drivers/1/location \
  -d '{"longitude":-73.985,"latitude":40.758}'

# 4. Run ad auction
curl -H "X-User-Id: user-100" \
  "http://localhost:8080/api/ads/serve?cuisine=Italian"

# 5. Place a food order (watch notification-worker logs)
curl -X POST -H "X-User-Id: user-100" -H "Content-Type: application/json" \
  http://localhost:8080/api/eats/orders \
  -d '{"restaurantId":"<id>","customerId":"user-100","items":[{"name":"Margherita","price":12.99}]}'

# 6. Request a ride (watch notification-worker logs)
curl -X POST -H "X-User-Id: user-100" -H "Content-Type: application/json" \
  http://localhost:8080/api/rides/rides \
  -d '{"userId":100,"pickupLat":40.758,"pickupLng":-73.985}'
```

The notification-worker terminal should show events from both steps 5 and 6 — demonstrating Kafka's decoupled async processing.
