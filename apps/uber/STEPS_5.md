# Phase 5 — Location Service (Port 8086, gRPC 9090)

Separated from Rides Service. Owns all driver location logic.

**Why separate?** Location updates = 200K writes/sec at scale. Ride booking = maybe 10K/sec. Different scale requirements, different protocols, different failure modes. Location data loss is recoverable in 5 seconds (next ping). Ride booking data loss is not.

**Protocols:**
- **Inbound (driver → service)**: gRPC bidirectional streaming. Driver app opens one persistent HTTP/2 stream and sends `LocationUpdate` messages every 5s. Server streams `RideAssignment` events back on the same connection.
- **Outbound (service → rider map)**: WebSocket. Rider app connects WebSocket; server polls Redis GEORADIUS every 2s and pushes nearby driver positions. Browser/mobile compatible.
- **Internal query (Rides/BFF → service)**: gRPC unary `FindNearbyDrivers`.

---

## Proto Definitions First

Create the shared proto directory:

```
apps/uber/proto/
├── BUILD.bazel
├── location.proto
└── adserving.proto   (used in STEPS_6)
```

### apps/uber/proto/location.proto

```protobuf
syntax = "proto3";
package uber.location;
option java_package = "com.uber.location.grpc";
option java_outer_classname = "LocationProto";
option java_multiple_files = true;

// Called by driver apps. Bidirectional streaming:
//   - Client sends: stream of LocationUpdate (every 5s)
//   - Server sends: stream of RideAssignment (when driver is matched)
// Also exposes a unary version for simpler clients and internal service calls.
service LocationService {
  rpc StreamLocation(stream LocationUpdate) returns (stream RideAssignment);
  rpc UpdateLocationUnary(LocationUpdate) returns (LocationAck);
  rpc FindNearbyDrivers(NearbyRequest) returns (NearbyResponse);
  rpc RemoveDriver(RemoveDriverRequest) returns (LocationAck);
}

message LocationUpdate {
  string driver_id = 1;
  double longitude  = 2;
  double latitude   = 3;
  int64  timestamp  = 4;  // epoch millis
}

message RideAssignment {
  string ride_id   = 1;
  string rider_id  = 2;
  double pickup_lat = 3;
  double pickup_lng = 4;
}

message LocationAck {
  bool   success = 1;
  string message = 2;
}

message NearbyRequest {
  double longitude   = 1;
  double latitude    = 2;
  double radius_km   = 3;
  int32  max_results = 4;
}

message NearbyResponse {
  repeated NearbyDriver drivers = 1;
}

message NearbyDriver {
  string driver_id    = 1;
  double longitude    = 2;
  double latitude     = 3;
  double distance_km  = 4;
}

message RemoveDriverRequest {
  string driver_id = 1;
}
```

### apps/uber/proto/BUILD.bazel

```python
# apps/uber/proto/BUILD.bazel
load("@rules_proto//proto:defs.bzl", "proto_library")

proto_library(
    name = "location_proto",
    srcs = ["location.proto"],
    visibility = ["//apps/uber:__subpackages__"],
)

proto_library(
    name = "adserving_proto",
    srcs = ["adserving.proto"],
    visibility = ["//apps/uber:__subpackages__"],
)
```

### Compile proto files manually (alternative to Bazel proto rules)

If Bazel proto compilation is complex to set up, generate Java files with protoc and commit them:

```bash
# Install protoc + grpc-java plugin
# Linux/WSL:
PB_REL="https://github.com/protocolbuffers/protobuf/releases"
curl -LO $PB_REL/download/v25.3/protoc-25.3-linux-x86_64.zip
unzip protoc-25.3-linux-x86_64.zip -d $HOME/.local

# Download grpc-java protoc plugin
curl -LO https://repo1.maven.org/maven2/io/grpc/protoc-gen-grpc-java/1.62.2/protoc-gen-grpc-java-1.62.2-linux-x86_64.exe
chmod +x protoc-gen-grpc-java-1.62.2-linux-x86_64.exe
mv protoc-gen-grpc-java-1.62.2-linux-x86_64.exe $HOME/.local/bin/protoc-gen-grpc-java

# Generate Java sources into location-service
mkdir -p apps/uber/location-service/src/main/java
protoc \
  --proto_path=apps/uber/proto \
  --java_out=apps/uber/location-service/src/main/java \
  --grpc-java_out=apps/uber/location-service/src/main/java \
  --plugin=protoc-gen-grpc-java=$HOME/.local/bin/protoc-gen-grpc-java \
  apps/uber/proto/location.proto

# Also generate into rides-service (it needs the client stub)
mkdir -p apps/uber/rides-service/src/main/java
protoc \
  --proto_path=apps/uber/proto \
  --java_out=apps/uber/rides-service/src/main/java \
  --grpc-java_out=apps/uber/rides-service/src/main/java \
  --plugin=protoc-gen-grpc-java=$HOME/.local/bin/protoc-gen-grpc-java \
  apps/uber/proto/location.proto

# Same for feed-bff
```

---

## Directory Structure

```
apps/uber/location-service/
├── BUILD.bazel
└── src/main/
    ├── java/com/uber/location/
    │   ├── LocationServiceApplication.java
    │   ├── RedisGeoConfig.java
    │   ├── KafkaProducerConfig.java
    │   ├── LocationGrpcService.java      ← gRPC server (bidirectional + unary)
    │   ├── LocationWebSocketHandler.java ← WebSocket push to rider map
    │   ├── WebSocketConfig.java
    │   └── DriverLocationStore.java      ← Redis GEO wrapper
    └── resources/
        └── application.properties
```

---

## File 1: BUILD.bazel

```python
# apps/uber/location-service/BUILD.bazel
load("@rules_java//java:defs.bzl", "java_binary", "java_library")

java_library(
    name = "location_lib",
    srcs = glob(["src/main/java/**/*.java"]),
    resources = glob(["src/main/resources/**"]),
    deps = [
        "@maven//:org_springframework_boot_spring_boot_starter_web",
        "@maven//:org_springframework_boot_spring_boot_starter_data_redis",
        "@maven//:org_springframework_boot_spring_boot_starter_websocket",
        "@maven//:org_springframework_kafka_spring_kafka",
        "@maven//:net_devh_grpc_spring_boot_starter",
        "@maven//:io_grpc_grpc_netty_shaded",
        "@maven//:io_grpc_grpc_protobuf",
        "@maven//:io_grpc_grpc_stub",
        "@maven//:com_google_protobuf_protobuf_java",
        "@maven//:javax_annotation_javax_annotation_api",
        "@maven//:com_fasterxml_jackson_core_jackson_databind",
    ],
)

java_binary(
    name = "location-service",
    main_class = "com.uber.location.LocationServiceApplication",
    runtime_deps = [":location_lib"],
)
```

---

## File 2: application.properties

```properties
server.port=8086
spring.application.name=location-service

# gRPC server port (separate from HTTP)
# net.devh starter starts a Netty gRPC server on this port alongside Tomcat HTTP.
grpc.server.port=9090

# Redis — driver location geo index
spring.data.redis.host=localhost
spring.data.redis.port=6379

# Kafka — publish location events for analytics consumers
spring.kafka.bootstrap-servers=localhost:9092

# WebSocket — rider map push interval
location.websocket.push-interval-ms=2000
location.geo.key=drivers:locations

logging.level.com.uber.location=DEBUG
logging.level.net.devh.boot.grpc=INFO
```

---

## File 3: LocationServiceApplication.java

```java
package com.uber.location;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling   // needed for WebSocket push scheduler
public class LocationServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(LocationServiceApplication.class, args);
    }
}
```

---

## File 4: RedisGeoConfig.java

```java
package com.uber.location;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisGeoConfig {

    @Bean
    public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new StringRedisSerializer());
        return template;
    }
}
```

---

## File 5: KafkaProducerConfig.java

```java
package com.uber.location;

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

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "0"); // fire-and-forget for location events
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
    }
}
```

---

## File 6: DriverLocationStore.java

```java
package com.uber.location;

// Redis GEO wrapper — all location storage logic in one place.
// GEOADD: O(log N) per write. GEORADIUS: O(N+log M) where N = candidates in bounding box.
// Data is ephemeral: if Redis restarts, drivers re-register within 5s on next ping.

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.geo.*;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class DriverLocationStore {

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Value("${location.geo.key}")
    private String geoKey;

    public void update(String driverId, double longitude, double latitude) {
        redisTemplate.opsForGeo().add(
                geoKey,
                new RedisGeoCommands.GeoLocation<>(driverId, new Point(longitude, latitude)));
    }

    public void remove(String driverId) {
        redisTemplate.opsForGeo().remove(geoKey, driverId);
    }

    public List<NearbyDriverInfo> findNearby(double longitude, double latitude,
                                              double radiusKm, int maxResults) {
        GeoResults<RedisGeoCommands.GeoLocation<String>> results =
                redisTemplate.opsForGeo().radius(
                        geoKey,
                        new Circle(new Point(longitude, latitude),
                                new Distance(radiusKm, Metrics.KILOMETERS)),
                        RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs()
                                .includeDistance()
                                .includeCoordinates()
                                .sortAscending()
                                .limit(maxResults));

        if (results == null) return Collections.emptyList();

        return results.getContent().stream().map(r -> {
            NearbyDriverInfo info = new NearbyDriverInfo();
            info.driverId = r.getContent().getName();
            info.longitude = r.getContent().getPoint().getX();
            info.latitude  = r.getContent().getPoint().getY();
            info.distanceKm = r.getDistance().getValue();
            return info;
        }).collect(Collectors.toList());
    }

    public static class NearbyDriverInfo {
        public String driverId;
        public double longitude;
        public double latitude;
        public double distanceKm;
    }
}
```

---

## File 7: LocationGrpcService.java

```java
package com.uber.location;

// gRPC service implementation.
// @GrpcService annotation (from net.devh starter) registers this as a gRPC service.
// The starter starts a Netty gRPC server on grpc.server.port alongside Tomcat HTTP.
//
// StreamLocation: bidirectional streaming.
//   - requestObserver is returned to the caller (driver sends into it).
//   - responseObserver is how we send back to the driver.
//   - We store responseObserver per driverId so RideService can push assignments.
//
// Thread safety: ConcurrentHashMap for active streams.

import com.uber.location.grpc.*;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.concurrent.ConcurrentHashMap;
import java.util.List;

@GrpcService
public class LocationGrpcService extends LocationServiceGrpc.LocationServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(LocationGrpcService.class);

    @Autowired private DriverLocationStore locationStore;
    @Autowired private KafkaTemplate<String, String> kafkaTemplate;

    @Value("${location.geo.key}")
    private String geoKey;

    // Map of driverId → responseObserver for active streaming connections.
    // Used to push RideAssignment events back to a specific driver.
    private final ConcurrentHashMap<String, StreamObserver<RideAssignment>> activeStreams =
            new ConcurrentHashMap<>();

    // ── Bidirectional streaming ─────────────────────────────────────────────
    @Override
    public StreamObserver<LocationUpdate> streamLocation(
            StreamObserver<RideAssignment> responseObserver) {

        // Return a StreamObserver that handles incoming LocationUpdate messages from the driver.
        return new StreamObserver<>() {

            private String currentDriverId = null;

            @Override
            public void onNext(LocationUpdate update) {
                currentDriverId = update.getDriverId();

                // Register stream so Ride Service can push assignments back
                activeStreams.put(currentDriverId, responseObserver);

                // Store location in Redis GEO
                locationStore.update(currentDriverId, update.getLongitude(), update.getLatitude());

                // Publish to Kafka for analytics consumers (fire-and-forget)
                String event = String.format(
                        "{\"driverId\":\"%s\",\"lng\":%.6f,\"lat\":%.6f,\"ts\":%d}",
                        currentDriverId, update.getLongitude(), update.getLatitude(),
                        update.getTimestamp());
                kafkaTemplate.send("location-events", currentDriverId, event);

                log.debug("Location updated: driver={} lng={} lat={}",
                        currentDriverId, update.getLongitude(), update.getLatitude());
            }

            @Override
            public void onError(Throwable t) {
                log.warn("Driver stream error for {}: {}", currentDriverId, t.getMessage());
                if (currentDriverId != null) {
                    activeStreams.remove(currentDriverId);
                    locationStore.remove(currentDriverId);
                }
            }

            @Override
            public void onCompleted() {
                log.info("Driver stream closed: {}", currentDriverId);
                if (currentDriverId != null) {
                    activeStreams.remove(currentDriverId);
                    locationStore.remove(currentDriverId);
                }
                responseObserver.onCompleted();
            }
        };
    }

    // ── Unary update (simpler clients, REST → gRPC bridge, testing) ─────────
    @Override
    public void updateLocationUnary(LocationUpdate request,
                                    StreamObserver<LocationAck> responseObserver) {
        locationStore.update(request.getDriverId(), request.getLongitude(), request.getLatitude());
        responseObserver.onNext(LocationAck.newBuilder().setSuccess(true).build());
        responseObserver.onCompleted();
    }

    // ── Find nearby drivers (called by Rides Service + Feed BFF) ────────────
    @Override
    public void findNearbyDrivers(NearbyRequest request,
                                  StreamObserver<NearbyResponse> responseObserver) {
        List<DriverLocationStore.NearbyDriverInfo> nearby = locationStore.findNearby(
                request.getLongitude(), request.getLatitude(),
                request.getRadiusKm(), request.getMaxResults());

        NearbyResponse.Builder response = NearbyResponse.newBuilder();
        for (DriverLocationStore.NearbyDriverInfo d : nearby) {
            response.addDrivers(NearbyDriver.newBuilder()
                    .setDriverId(d.driverId)
                    .setLongitude(d.longitude)
                    .setLatitude(d.latitude)
                    .setDistanceKm(d.distanceKm)
                    .build());
        }
        responseObserver.onNext(response.build());
        responseObserver.onCompleted();
    }

    // ── Called by RideService to push a ride assignment to a driver ─────────
    public boolean pushRideAssignment(String driverId, String rideId, String riderId,
                                      double pickupLat, double pickupLng) {
        StreamObserver<RideAssignment> stream = activeStreams.get(driverId);
        if (stream == null) return false; // driver not connected via streaming

        stream.onNext(RideAssignment.newBuilder()
                .setRideId(rideId)
                .setRiderId(riderId)
                .setPickupLat(pickupLat)
                .setPickupLng(pickupLng)
                .build());
        return true;
    }

    @Override
    public void removeDriver(RemoveDriverRequest request,
                             StreamObserver<LocationAck> responseObserver) {
        locationStore.remove(request.getDriverId());
        activeStreams.remove(request.getDriverId());
        responseObserver.onNext(LocationAck.newBuilder().setSuccess(true).build());
        responseObserver.onCompleted();
    }
}
```

---

## File 8: WebSocketConfig.java

```java
package com.uber.location;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    @Bean
    public LocationWebSocketHandler locationWebSocketHandler() {
        return new LocationWebSocketHandler();
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Riders connect to ws://localhost:8086/ws/drivers?lat=40.75&lng=-73.98&radius=3
        registry.addHandler(locationWebSocketHandler(), "/ws/drivers")
                .setAllowedOrigins("*");
    }
}
```

---

## File 9: LocationWebSocketHandler.java

```java
package com.uber.location;

// WebSocket handler for rider-side live map.
// Each connected rider's session is stored with their search coordinates.
// A scheduler pushes updated nearby driver positions every 2 seconds.
//
// Why WebSocket here and not gRPC streaming?
// WebSocket is browser and mobile SDK native.
// gRPC requires grpc-web proxy for browsers.
// The rider map is a UI concern — WebSocket is the right protocol.

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class LocationWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(LocationWebSocketHandler.class);

    @Autowired(required = false)
    private DriverLocationStore locationStore;

    @Autowired(required = false)
    private ObjectMapper objectMapper;

    @Value("${location.websocket.push-interval-ms:2000}")
    private long pushIntervalMs;

    // sessionId → RiderSession (holds WebSocketSession + search params)
    private final Map<String, RiderSession> sessions = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        URI uri = session.getUri();
        if (uri == null) return;

        Map<String, String> params = parseQuery(uri.getQuery());
        double lat = Double.parseDouble(params.getOrDefault("lat", "0"));
        double lng = Double.parseDouble(params.getOrDefault("lng", "0"));
        double radius = Double.parseDouble(params.getOrDefault("radius", "5"));

        sessions.put(session.getId(), new RiderSession(session, lat, lng, radius));
        log.info("Rider WebSocket connected: session={} lat={} lng={}", session.getId(), lat, lng);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());
        log.info("Rider WebSocket disconnected: session={}", session.getId());
    }

    // Runs every push-interval-ms. Queries Redis GEO for each connected rider,
    // pushes nearby driver positions as JSON.
    @Scheduled(fixedDelayString = "${location.websocket.push-interval-ms:2000}")
    public void pushDriverPositions() {
        if (locationStore == null || sessions.isEmpty()) return;

        sessions.values().forEach(rs -> {
            if (!rs.session.isOpen()) return;
            try {
                List<DriverLocationStore.NearbyDriverInfo> nearby =
                        locationStore.findNearby(rs.lng, rs.lat, rs.radiusKm, 20);

                String json = objectMapper.writeValueAsString(Map.of(
                        "type", "drivers",
                        "drivers", nearby));

                rs.session.sendMessage(new TextMessage(json));
            } catch (Exception e) {
                log.warn("Failed to push to rider {}: {}", rs.session.getId(), e.getMessage());
            }
        });
    }

    private Map<String, String> parseQuery(String query) {
        Map<String, String> map = new ConcurrentHashMap<>();
        if (query == null) return map;
        for (String part : query.split("&")) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2) map.put(kv[0], kv[1]);
        }
        return map;
    }

    private static class RiderSession {
        WebSocketSession session;
        double lat, lng, radiusKm;
        RiderSession(WebSocketSession s, double lat, double lng, double r) {
            this.session = s; this.lat = lat; this.lng = lng; this.radiusKm = r;
        }
    }
}
```

---

## Updated Rides Service — Calls Location via gRPC

Add `LocationGrpcClient.java` to the Rides Service and update `RideService.java`:

### LocationGrpcClient.java (in rides-service)

```java
package com.uber.rides;

import com.uber.location.grpc.*;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class LocationGrpcClient {

    // @GrpcClient("location-service" matches grpc.client.location-service.address in properties
    @GrpcClient("location-service")
    private LocationServiceGrpc.LocationServiceBlockingStub locationStub;

    public List<String> findNearbyDriverIds(double longitude, double latitude,
                                            double radiusKm, int maxResults) {
        NearbyResponse response = locationStub.findNearbyDrivers(
                NearbyRequest.newBuilder()
                        .setLongitude(longitude)
                        .setLatitude(latitude)
                        .setRadiusKm(radiusKm)
                        .setMaxResults(maxResults)
                        .build());

        return response.getDriversList().stream()
                .map(NearbyDriver::getDriverId)
                .collect(Collectors.toList());
    }
}
```

### rides-service application.properties additions:

```properties
# gRPC client — points to Location Service
grpc.client.location-service.address=static://localhost:9090
grpc.client.location-service.negotiation-type=plaintext
```

### RideService.java — replace direct Redis call:

```java
// Old (direct Redis GEO):
// List<String> nearbyDriverIds = locationService.findNearbyDrivers(lng, lat);

// New (gRPC call to Location Service):
List<String> nearbyDriverIds = locationGrpcClient.findNearbyDriverIds(
    pickupLng, pickupLat, 5.0, 10);
```

---

## Verification

```bash
bazel build //apps/uber/location-service:location-service
./bazel-bin/apps/uber/location-service/location-service

# ── Test gRPC unary (requires grpcurl) ─────────────────────────────────────
brew install grpcurl   # or: go install github.com/fullstorydev/grpcurl/cmd/grpcurl@latest

grpcurl -plaintext \
  -d '{"driver_id":"driver-1","longitude":-73.985,"latitude":40.758,"timestamp":1712000000000}' \
  localhost:9090 uber.location.LocationService/UpdateLocationUnary

grpcurl -plaintext \
  -d '{"longitude":-73.985,"latitude":40.758,"radius_km":5.0,"max_results":5}' \
  localhost:9090 uber.location.LocationService/FindNearbyDrivers

# ── Test WebSocket (requires wscat) ────────────────────────────────────────
npm install -g wscat
wscat -c "ws://localhost:8086/ws/drivers?lat=40.758&lng=-73.985&radius=5"
# Should receive JSON with nearby drivers every 2 seconds

# ── Test bidirectional streaming ────────────────────────────────────────────
# grpcurl supports bidi streaming:
grpcurl -plaintext -d @ localhost:9090 uber.location.LocationService/StreamLocation <<EOF
{"driver_id":"driver-2","longitude":-73.990,"latitude":40.760,"timestamp":1712000000000}
{"driver_id":"driver-2","longitude":-73.991,"latitude":40.761,"timestamp":1712000005000}
EOF
```

Continue to `STEPS_6.md` for Campaign Management with fixed-bid model.
