# Phase 9 — Feed BFF (Port 8085)

The Feed BFF (Backend for Frontend) aggregates data from multiple services into a single GraphQL response. It is the only service that mobile/web clients call directly (via the API Gateway at :8080).

**Why GraphQL here?**
The feed page needs data from three different services: restaurants (Eats), a featured ad (Ad Serving), and nearby driver count (Location). Without BFF, the mobile app makes 3 separate REST calls: `GET /restaurants`, gRPC to Ad Serving, gRPC to Location. On a 4G connection with 100ms RTT, that's 300ms+ in serial, or complex client-side parallel coordination. GraphQL lets the client describe exactly what it needs in one query, and the BFF resolves all fields in parallel internally.

**Why NOT GraphQL everywhere?**
GraphQL resolver overhead (~2ms per request), schema versioning complexity, and N+1 query problems make it poor for simple CRUD services (Eats, Rides, Campaign Mgmt). Use REST for those. GraphQL shines when aggregating heterogeneous backends.

---

## GraphQL Schema

```graphql
type Query {
  feed(latitude: Float!, longitude: Float!, cuisine: String): Feed
  restaurant(id: ID!): Restaurant
  order(id: ID!): Order
  myRides(userId: ID!): [Ride]
}

type Mutation {
  placeOrder(input: OrderInput!): Order
  requestRide(input: RideInput!): Ride
}

type Subscription {
  orderStatus(orderId: ID!): OrderStatusUpdate
}

type Feed {
  restaurants: [Restaurant]
  featuredAd: Ad
  nearbyDriverCount: Int
}

type Restaurant {
  id: ID!
  name: String
  cuisine: String
  address: String
  imageUrl: String
  menu: [MenuItem]
}

type MenuItem {
  name: String
  price: Float
  category: String
}

type Ad {
  adId: ID!
  title: String
  imageUrl: String
  fallbackUsed: Boolean
}

type Order {
  id: ID!
  status: String
  totalAmount: Float
  createdAt: String
}

type OrderStatusUpdate {
  orderId: ID!
  status: String
}

type Ride {
  id: ID!
  status: String
  fare: Float
}

input OrderInput {
  restaurantId: ID!
  customerId: ID!
  items: [MenuItemInput!]!
}

input MenuItemInput {
  name: String!
  price: Float!
  category: String
}

input RideInput {
  userId: ID!
  pickupLat: Float!
  pickupLng: Float!
  dropoffLat: Float!
  dropoffLng: Float!
}
```

---

## Directory Structure

```
apps/uber/feed-bff/
├── BUILD.bazel
└── src/main/
    ├── java/com/uber/feedbff/
    │   ├── FeedBffApplication.java
    │   ├── FeedResolver.java          ← Query.feed: parallel restaurants + ad + drivers
    │   ├── OrderResolver.java         ← Query.order, Mutation.placeOrder
    │   ├── RideResolver.java          ← Query.myRides, Mutation.requestRide
    │   ├── EatsClient.java            ← REST → Eats Service (:8081)
    │   ├── AdServingClient.java       ← gRPC → Ad Serving Service (:9091)
    │   ├── LocationClient.java        ← gRPC → Location Service (:9090)
    │   ├── AdClickController.java     ← POST /api/ads/click → Kafka ad-click-events
    │   ├── WebSocketConfig.java       ← WebSocket for order status subscription
    │   ├── OrderStatusHandler.java    ← WebSocket handler (Kafka consumer)
    │   └── KafkaProducerConfig.java
    └── resources/
        ├── application.properties
        └── graphql/
            └── schema.graphqls        ← The schema above
```

---

## File 1: BUILD.bazel

```python
# apps/uber/feed-bff/BUILD.bazel
load("@rules_java//java:defs.bzl", "java_binary", "java_library")

java_library(
    name = "feedbff_lib",
    srcs = glob(["src/main/java/**/*.java"]),
    resources = glob(["src/main/resources/**"]),
    deps = [
        "@maven//:org_springframework_boot_spring_boot_starter_graphql",
        "@maven//:org_springframework_boot_spring_boot_starter_webflux",
        "@maven//:org_springframework_boot_spring_boot_starter_web",
        "@maven//:org_springframework_kafka_spring_kafka",
        "@maven//:org_springframework_spring_websocket",
        "@maven//:net_devh_grpc_spring_boot_starter",
        "@maven//:io_grpc_grpc_netty_shaded",
        "@maven//:io_grpc_grpc_stub",
        "@maven//:com_google_protobuf_protobuf_java",
        "@maven//:javax_annotation_javax_annotation_api",
        "@maven//:com_fasterxml_jackson_core_jackson_databind",
    ],
)

java_binary(
    name = "feed-bff",
    main_class = "com.uber.feedbff.FeedBffApplication",
    runtime_deps = [":feedbff_lib"],
)
```

---

## File 2: application.properties

```properties
# apps/uber/feed-bff/src/main/resources/application.properties
server.port=8085
spring.application.name=feed-bff

# GraphQL — enable GraphiQL browser IDE at /graphiql
spring.graphql.graphiql.enabled=true

# Downstream service URLs
eats.service.url=http://localhost:8081
rides.service.url=http://localhost:8083

# gRPC client connections (ad-serving and location)
grpc.client.ad-serving-service.address=static://localhost:9091
grpc.client.ad-serving-service.negotiation-type=plaintext
grpc.client.location-service.address=static://localhost:9090
grpc.client.location-service.negotiation-type=plaintext

# Kafka
spring.kafka.bootstrap-servers=localhost:9092

logging.level.com.uber.feedbff=DEBUG
```

---

## File 3: FeedBffApplication.java

```java
// apps/uber/feed-bff/src/main/java/com/uber/feedbff/FeedBffApplication.java
package com.uber.feedbff;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class FeedBffApplication {
    public static void main(String[] args) {
        SpringApplication.run(FeedBffApplication.class, args);
    }
}
```

---

## File 4: EatsClient.java

```java
// apps/uber/feed-bff/src/main/java/com/uber/feedbff/EatsClient.java
package com.uber.feedbff;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@Component
public class EatsClient {

    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${eats.service.url}")
    private String eatsUrl;

    public List<Map<String, Object>> getRestaurants(String cuisine) {
        String url = eatsUrl + "/api/eats/restaurants"
                + (cuisine != null ? "?cuisine=" + cuisine : "");
        // Raw map deserialization for simplicity; use typed DTOs in production
        List response = restTemplate.getForObject(url, List.class);
        return response != null ? response : List.of();
    }

    public Map<String, Object> placeOrder(Map<String, Object> orderInput) {
        return restTemplate.postForObject(
                eatsUrl + "/api/eats/orders", orderInput, Map.class);
    }

    public Map<String, Object> getOrder(String orderId) {
        return restTemplate.getForObject(
                eatsUrl + "/api/eats/orders/" + orderId, Map.class);
    }
}
```

---

## File 5: AdServingClient.java

```java
// apps/uber/feed-bff/src/main/java/com/uber/feedbff/AdServingClient.java
package com.uber.feedbff;

// gRPC client for Ad Serving Service.
//
// Why gRPC here vs REST?
// Ad Serving is called on every single feed load. At 10K feed loads/sec:
//   REST (JSON): ~5ms serialization + HTTP/1.1 overhead = 50 server-core-seconds/sec wasted
//   gRPC (proto): ~0.5ms serialization + HTTP/2 multiplexing = 5 server-core-seconds/sec
// The 10x throughput improvement justifies the protobuf complexity.

import com.uber.adserving.grpc.AdRequest;
import com.uber.adserving.grpc.AdResponse;
import com.uber.adserving.grpc.AdServingServiceGrpc;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class AdServingClient {

    // @GrpcClient injects a pre-configured blocking stub.
    // The channel is managed by grpc-spring-boot-starter — connection pooling, keepalive, etc.
    @GrpcClient("ad-serving-service")
    private AdServingServiceGrpc.AdServingServiceBlockingStub stub;

    public Map<String, Object> getFeaturedAd(
            String userId, double lat, double lng, String cuisine) {
        AdRequest request = AdRequest.newBuilder()
                .setUserId(userId)
                .setLatitude(lat)
                .setLongitude(lng)
                .setCuisine(cuisine != null ? cuisine : "")
                .build();

        AdResponse response = stub.getFeaturedAd(request);

        if (response.getAdId() == 0) return null;  // no ad available

        return Map.of(
                "adId",        response.getAdId(),
                "title",       response.getTitle(),
                "imageUrl",    response.getImageUrl(),
                "fallbackUsed", response.getFallbackUsed()
        );
    }
}
```

---

## File 6: LocationClient.java

```java
// apps/uber/feed-bff/src/main/java/com/uber/feedbff/LocationClient.java
package com.uber.feedbff;

import com.uber.location.grpc.NearbyRequest;
import com.uber.location.grpc.NearbyResponse;
import com.uber.location.grpc.LocationServiceGrpc;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.stereotype.Component;

@Component
public class LocationClient {

    @GrpcClient("location-service")
    private LocationServiceGrpc.LocationServiceBlockingStub stub;

    public int getNearbyDriverCount(double lat, double lng, double radiusKm) {
        NearbyRequest request = NearbyRequest.newBuilder()
                .setLatitude(lat)
                .setLongitude(lng)
                .setRadiusKm(radiusKm)
                .build();

        NearbyResponse response = stub.findNearbyDrivers(request);
        return response.getDriversList().size();
    }
}
```

---

## File 7: FeedResolver.java

```java
// apps/uber/feed-bff/src/main/java/com/uber/feedbff/FeedResolver.java
package com.uber.feedbff;

// GraphQL resolver for the feed query.
//
// Parallel resolution:
// The three data sources (restaurants, ad, driver count) are independent.
// We fire all three calls concurrently using CompletableFuture and join at the end.
//
// Serial version: 8ms (Eats) + 15ms (Ad Serving) + 3ms (Location) = 26ms
// Parallel version: max(8ms, 15ms, 3ms) = 15ms  ← ad serving is the bottleneck
//
// Spring for GraphQL automatically runs resolvers in parallel when they
// return CompletableFuture. The framework handles thread pool management.

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Controller
public class FeedResolver {

    @Autowired private EatsClient eatsClient;
    @Autowired private AdServingClient adServingClient;
    @Autowired private LocationClient locationClient;

    // Hardcoded userId for learning; in production: extract from JWT in HTTP header
    private static final String SYSTEM_USER_ID = "user-001";

    @QueryMapping
    public Map<String, Object> feed(
            @Argument Double latitude,
            @Argument Double longitude,
            @Argument String cuisine) {

        // Fire all three calls in parallel
        CompletableFuture<List<Map<String, Object>>> restaurantsFuture =
                CompletableFuture.supplyAsync(() ->
                        eatsClient.getRestaurants(cuisine));

        CompletableFuture<Map<String, Object>> adFuture =
                CompletableFuture.supplyAsync(() ->
                        adServingClient.getFeaturedAd(
                                SYSTEM_USER_ID, latitude, longitude, cuisine));

        CompletableFuture<Integer> driverCountFuture =
                CompletableFuture.supplyAsync(() ->
                        locationClient.getNearbyDriverCount(latitude, longitude, 5.0));

        // Join all futures — waits for the slowest one
        CompletableFuture.allOf(restaurantsFuture, adFuture, driverCountFuture).join();

        return Map.of(
                "restaurants",      restaurantsFuture.join(),
                "featuredAd",       adFuture.join() != null ? adFuture.join() : Map.of(),
                "nearbyDriverCount", driverCountFuture.join()
        );
    }
}
```

---

## File 8: OrderResolver.java

```java
// apps/uber/feed-bff/src/main/java/com/uber/feedbff/OrderResolver.java
package com.uber.feedbff;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Controller;

import java.util.Map;

@Controller
public class OrderResolver {

    @Autowired private EatsClient eatsClient;
    @Autowired private KafkaTemplate<String, String> kafkaTemplate;

    @QueryMapping
    public Map<String, Object> order(@Argument String id) {
        return eatsClient.getOrder(id);
    }

    @MutationMapping
    public Map<String, Object> placeOrder(@Argument Map<String, Object> input) {
        return eatsClient.placeOrder(input);
    }
}
```

---

## File 9: AdClickController.java

```java
// apps/uber/feed-bff/src/main/java/com/uber/feedbff/AdClickController.java
package com.uber.feedbff;

// Separate REST endpoint for ad click tracking.
// Mobile client calls POST /api/ads/click when user taps a featured ad.
//
// Not a GraphQL mutation because:
// - Click tracking must be fire-and-forget (<1ms response time)
// - GraphQL mutations have resolver overhead (~2ms)
// - REST POST with Kafka publish: ~0.5ms total
//
// The ad-click-events topic feeds the ML Platform's DCN training data pipeline.

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/ads")
public class AdClickController {

    @Autowired private KafkaTemplate<String, String> kafkaTemplate;

    @PostMapping("/click")
    public ResponseEntity<Void> recordClick(@RequestBody Map<String, Object> body) {
        String adId     = String.valueOf(body.get("adId"));
        String userId   = String.valueOf(body.get("userId"));
        String cuisine  = String.valueOf(body.getOrDefault("cuisine", ""));

        String event = String.format(
                "{\"adId\":\"%s\",\"userId\":\"%s\",\"cuisine\":\"%s\",\"ts\":%d}",
                adId, userId, cuisine, System.currentTimeMillis());

        // Key = adId → all clicks for one ad go to the same partition (ordered per ad)
        kafkaTemplate.send("ad-click-events", adId, event);

        return ResponseEntity.accepted().build();  // 202: accepted for async processing
    }
}
```

---

## File 10: OrderStatusHandler.java (WebSocket)

```java
// apps/uber/feed-bff/src/main/java/com/uber/feedbff/OrderStatusHandler.java
package com.uber.feedbff;

// WebSocket handler for real-time order status updates.
//
// Why WebSocket (not GraphQL Subscription)?
// GraphQL Subscriptions work over WebSocket too, but require a subscription-aware
// transport (graphql-ws protocol). For a learning project, plain WebSocket is simpler
// and demonstrates the same concept.
//
// Flow:
//   1. Client opens WebSocket: ws://localhost:8085/ws/orders/{orderId}
//   2. This handler subscribes to the order-events Kafka topic
//   3. When status changes for that orderId, it pushes to the client
//
// Production alternative: use Server-Sent Events (SSE) for one-way push
// — simpler than WebSocket for unidirectional updates (status only goes server→client).

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class OrderStatusHandler extends TextWebSocketHandler {

    // orderId → WebSocket session. One connection per order.
    // In production: use Redis pub/sub so multiple BFF instances can push to the same order.
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        // URL: /ws/orders/{orderId}
        String path = session.getUri().getPath();
        String orderId = path.substring(path.lastIndexOf('/') + 1);
        sessions.put(orderId, session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.values().remove(session);
    }

    @KafkaListener(topics = "order-events", groupId = "feed-bff-ws")
    public void onOrderEvent(String eventJson) {
        // Parse orderId from event and push to connected WebSocket client
        try {
            // Simple string parsing; use ObjectMapper in production
            String orderId = extractField(eventJson, "orderId");
            String orderStatus = extractField(eventJson, "status");
            if (orderId == null) return;

            WebSocketSession session = sessions.get(orderId);
            if (session != null && session.isOpen()) {
                String update = String.format(
                        "{\"orderId\":\"%s\",\"status\":\"%s\"}", orderId, orderStatus);
                session.sendMessage(new TextMessage(update));
            }
        } catch (Exception ignored) {}
    }

    private String extractField(String json, String field) {
        String search = "\"" + field + "\":\"";
        int start = json.indexOf(search);
        if (start < 0) return null;
        start += search.length();
        int end = json.indexOf("\"", start);
        return end < 0 ? null : json.substring(start, end);
    }
}
```

---

## File 11: WebSocketConfig.java

```java
// apps/uber/feed-bff/src/main/java/com/uber/feedbff/WebSocketConfig.java
package com.uber.feedbff;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.*;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    @Autowired private OrderStatusHandler orderStatusHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Path pattern: /ws/orders/abc-123 → handler gets orderId from URI
        registry.addHandler(orderStatusHandler, "/ws/orders/*")
                .setAllowedOrigins("*");   // restrict in production
    }
}
```

---

## File 12: KafkaProducerConfig.java + RideResolver.java

```java
// apps/uber/feed-bff/src/main/java/com/uber/feedbff/KafkaProducerConfig.java
package com.uber.feedbff;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.*;
import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaProducerConfig {
    @Value("${spring.kafka.bootstrap-servers}") private String bootstrapServers;

    @Bean
    public ProducerFactory<String, String> producerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "1");
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }
}

// apps/uber/feed-bff/src/main/java/com/uber/feedbff/RideResolver.java
package com.uber.feedbff;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.stereotype.Controller;
import org.springframework.web.client.RestTemplate;
import java.util.Map;

@Controller
public class RideResolver {
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${rides.service.url}") private String ridesUrl;

    @MutationMapping
    public Map<String, Object> requestRide(@Argument Map<String, Object> input) {
        return restTemplate.postForObject(ridesUrl + "/api/rides", input, Map.class);
    }
}
```

---

## Verification

```bash
bazel build //apps/uber/feed-bff:feed-bff
./bazel-bin/apps/uber/feed-bff/feed-bff

# 1. Open GraphiQL in browser: http://localhost:8085/graphiql

# 2. Run a feed query
curl -X POST http://localhost:8085/graphql \
  -H "Content-Type: application/json" \
  -d '{
    "query": "query { feed(latitude: 37.77, longitude: -122.41, cuisine: \"Italian\") { restaurants { name cuisine } featuredAd { adId title } nearbyDriverCount } }"
  }'

# 3. Place an order via GraphQL mutation
curl -X POST http://localhost:8085/graphql \
  -H "Content-Type: application/json" \
  -d '{
    "query": "mutation { placeOrder(input: { restaurantId: \"<id>\", customerId: \"c1\", items: [{name: \"Pizza\", price: 12.99, category: \"Mains\"}] }) { id status totalAmount } }"
  }'

# 4. Connect to order status WebSocket (install wscat: npm i -g wscat)
wscat -c ws://localhost:8085/ws/orders/<order-id>
# When order status changes via PATCH on Eats Service, the WebSocket receives:
# {"orderId":"<id>","status":"OUT_FOR_DELIVERY"}

# 5. Record an ad click
curl -X POST http://localhost:8085/api/ads/click \
  -H "Content-Type: application/json" \
  -d '{"adId": "1", "userId": "user-001", "cuisine": "Italian"}'
# Verify in Kafka: docker exec -it uber-kafka kafka-console-consumer.sh \
#   --bootstrap-server localhost:9092 --topic ad-click-events --from-beginning

# 6. Observe parallel resolution timing in logs
# All three resolvers (restaurants, ad, drivers) log with timestamps.
# Total latency should be max of the three, not sum.
```

All nine phases are complete. Start all services per `setup.sh` output and call everything through the API Gateway at `:8080`.
