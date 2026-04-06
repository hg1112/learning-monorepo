# Phase 3 — Rides Service (Port 8083)

Handles driver registration, live location tracking, and ride matching.

**Tech**: PostgreSQL + Citus (rides/drivers data, ACID for booking), Redis GEO (live driver locations, sub-millisecond radius search), Kafka (ride events).

**Key concepts**:
- Driver location lives in Redis (`GEOADD`/`GEORADIUS`), NOT in PostgreSQL. At 1M drivers pinging every 5s, that's 200K writes/sec — Redis handles it single-threaded; PostgreSQL would need 40+ nodes.
- The ride record (matching, status, fare) lives in PostgreSQL for ACID durability.
- Citus shards the `rides` table by `user_id`. All rides for one user live on one shard.
- `drivers` is a Citus reference table — replicated to all shards (small table, needs to JOIN with rides).

---

## Directory Structure

```
apps/uber/rides-service/
├── BUILD.bazel
└── src/main/java/com/uber/rides/
    ├── RidesServiceApplication.java
    ├── Driver.java
    ├── Ride.java
    ├── DriverRepository.java
    ├── RideRepository.java
    ├── LocationService.java
    ├── RideService.java
    ├── DriverController.java
    ├── LocationController.java
    ├── RideController.java
    ├── RedisConfig.java
    └── KafkaProducerConfig.java
    resources/application.properties
```

---

## File 1: BUILD.bazel

```python
# apps/uber/rides-service/BUILD.bazel
load("@rules_java//java:defs.bzl", "java_binary", "java_library")

java_library(
    name = "rides_lib",
    srcs = glob(["src/main/java/**/*.java"]),
    resources = glob(["src/main/resources/**"]),
    deps = [
        "@maven//:org_springframework_boot_spring_boot_starter_web",
        "@maven//:org_springframework_boot_spring_boot_starter_data_jpa",
        "@maven//:org_springframework_boot_spring_boot_starter_data_redis",
        "@maven//:org_springframework_kafka_spring_kafka",
        "@maven//:org_postgresql_postgresql",
        "@maven//:com_fasterxml_jackson_core_jackson_databind",
    ],
)

java_binary(
    name = "rides-service",
    main_class = "com.uber.rides.RidesServiceApplication",
    runtime_deps = [":rides_lib"],
)
```

---

## File 2: application.properties

```properties
# apps/uber/rides-service/src/main/resources/application.properties
server.port=8083
spring.application.name=rides-service

# PostgreSQL (Citus coordinator) — app sees a normal Postgres connection.
# Citus distributes rows transparently across worker shards.
spring.datasource.url=jdbc:postgresql://localhost:5432/uber
spring.datasource.username=uber
spring.datasource.password=uber
spring.datasource.driver-class-name=org.postgresql.Driver

spring.jpa.hibernate.ddl-auto=validate
spring.jpa.show-sql=false
spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect

# Redis — driver location store and general cache
spring.data.redis.host=localhost
spring.data.redis.port=6379

# Kafka
spring.kafka.bootstrap-servers=localhost:9092

# Matching config
rides.matching.radius-km=5.0
rides.matching.max-results=5

logging.level.com.uber.rides=DEBUG
```

---

## File 3: RidesServiceApplication.java

```java
// apps/uber/rides-service/src/main/java/com/uber/rides/RidesServiceApplication.java
package com.uber.rides;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class RidesServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(RidesServiceApplication.class, args);
    }
}
```

---

## File 4: RedisConfig.java

```java
// apps/uber/rides-service/src/main/java/com/uber/rides/RedisConfig.java
package com.uber.rides;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {

    // The Rides service uses Redis primarily for GEO operations.
    // GEO commands store member names as strings and coordinates as geohash integers.
    // A plain StringRedisTemplate works, but RedisTemplate<String, String> gives
    // us access to opsForGeo() which returns typed GeoResults.
    @Bean
    public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new StringRedisSerializer());
        return template;
    }
}
```

---

## File 5: KafkaProducerConfig.java

```java
// apps/uber/rides-service/src/main/java/com/uber/rides/KafkaProducerConfig.java
package com.uber.rides;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaProducerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

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
```

---

## File 6: Driver.java

```java
// apps/uber/rides-service/src/main/java/com/uber/rides/Driver.java
package com.uber.rides;

import jakarta.persistence.*;

// Citus reference table: replicated to every shard worker.
// Small table (drivers list) that gets JOINed with rides.
// In Citus, reference tables are co-located on all shards to avoid cross-shard JOINs.
// The DDL in STEPS.md uses: SELECT create_reference_table('drivers');
@Entity
@Table(name = "drivers")
public class Driver {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(name = "vehicle_plate")
    private String vehiclePlate;

    // AVAILABLE | BUSY | OFFLINE
    // This is the persistent status (survives restarts).
    // Live location is in Redis — ephemeral, ultra-fast.
    @Column(nullable = false)
    private String status = "AVAILABLE";

    // Getters and Setters
    public Long getId()                        { return id; }
    public void setId(Long id)                 { this.id = id; }
    public String getName()                    { return name; }
    public void setName(String name)           { this.name = name; }
    public String getVehiclePlate()            { return vehiclePlate; }
    public void setVehiclePlate(String plate)  { this.vehiclePlate = plate; }
    public String getStatus()                  { return status; }
    public void setStatus(String status)       { this.status = status; }
}
```

---

## File 7: Ride.java

```java
// apps/uber/rides-service/src/main/java/com/uber/rides/Ride.java
package com.uber.rides;

import jakarta.persistence.*;
import java.time.Instant;

// Citus distributed table: sharded by user_id.
// All rides for one customer on one shard → fast "my trip history" queries.
// The DDL: SELECT create_distributed_table('rides', 'user_id');
@Entity
@Table(name = "rides")
public class Ride {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Shard key — must be present in all queries that need to stay on one shard.
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "driver_id")
    private Long driverId;

    @Column(name = "pickup_lat",  nullable = false)
    private double pickupLat;

    @Column(name = "pickup_lng",  nullable = false)
    private double pickupLng;

    @Column(name = "dropoff_lat")
    private double dropoffLat;

    @Column(name = "dropoff_lng")
    private double dropoffLng;

    // REQUESTED | MATCHED | IN_PROGRESS | COMPLETED | CANCELLED
    @Column(nullable = false)
    private String status = "REQUESTED";

    private double fare;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    // Getters and Setters
    public Long getId()                         { return id; }
    public void setId(Long id)                  { this.id = id; }
    public Long getUserId()                     { return userId; }
    public void setUserId(Long uid)             { this.userId = uid; }
    public Long getDriverId()                   { return driverId; }
    public void setDriverId(Long did)           { this.driverId = did; }
    public double getPickupLat()                { return pickupLat; }
    public void setPickupLat(double lat)        { this.pickupLat = lat; }
    public double getPickupLng()                { return pickupLng; }
    public void setPickupLng(double lng)        { this.pickupLng = lng; }
    public double getDropoffLat()               { return dropoffLat; }
    public void setDropoffLat(double lat)       { this.dropoffLat = lat; }
    public double getDropoffLng()               { return dropoffLng; }
    public void setDropoffLng(double lng)       { this.dropoffLng = lng; }
    public String getStatus()                   { return status; }
    public void setStatus(String status)        { this.status = status; }
    public double getFare()                     { return fare; }
    public void setFare(double fare)            { this.fare = fare; }
    public Instant getCreatedAt()               { return createdAt; }
    public void setCreatedAt(Instant t)         { this.createdAt = t; }
}
```

---

## File 8: DriverRepository.java

```java
// apps/uber/rides-service/src/main/java/com/uber/rides/DriverRepository.java
package com.uber.rides;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DriverRepository extends JpaRepository<Driver, Long> {
    List<Driver> findByStatus(String status);
}
```

---

## File 9: RideRepository.java

```java
// apps/uber/rides-service/src/main/java/com/uber/rides/RideRepository.java
package com.uber.rides;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RideRepository extends JpaRepository<Ride, Long> {

    // Citus: this query includes user_id in WHERE → hits exactly one shard.
    // Fast single-shard query.
    List<Ride> findByUserId(Long userId);

    // This query does NOT include user_id → Citus fans out to all shards.
    // Acceptable for a driver's current active ride (infrequent + small result).
    List<Ride> findByDriverIdAndStatus(Long driverId, String status);
}
```

---

## File 10: LocationService.java

```java
// apps/uber/rides-service/src/main/java/com/uber/rides/LocationService.java
package com.uber.rides;

// Redis GEO commands — the core of real-time driver tracking.
//
// GEOADD key longitude latitude member
//   Stores a point using a Geohash encoding. O(log N) per write.
//   The "members" are driver IDs (strings).
//
// GEORADIUS key longitude latitude radius unit [COUNT n] [ASC]
//   Returns members within radius, optionally sorted by distance.
//   O(N + log M) where N = members in the bounding box, M = result count.
//   For 1M drivers, typical radius search examines ~hundreds of candidates.
//
// Why Redis GEO over PostGIS?
//   Redis GEO write: ~0.1ms (in-memory). PostGIS update: ~2-10ms (disk WAL).
//   At 200K location writes/sec: Redis = 20 concurrent ops; Postgres = 2000 connections → crash.
//   Acceptable tradeoff: If Redis restarts, all locations lost. Drivers re-register
//   in 5 seconds on their next location ping. No permanent data loss.

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class LocationService {

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    // All driver locations stored in a single sorted set under this key.
    // The set is not partitioned — all 1M drivers live here.
    // In production you'd shard by city (e.g., "drivers:locations:NYC").
    private static final String GEO_KEY = "drivers:locations";

    @Value("${rides.matching.radius-km}")
    private double radiusKm;

    @Value("${rides.matching.max-results}")
    private int maxResults;

    // Called every time a driver sends a location update (every 5 seconds).
    // Redis GEOADD overwrites the member if it already exists —
    // the sorted set score (geohash integer) is updated in place.
    public void updateLocation(String driverId, double longitude, double latitude) {
        redisTemplate.opsForGeo().add(
                GEO_KEY,
                new RedisGeoCommands.GeoLocation<>(driverId, new Point(longitude, latitude)));
    }

    public void removeDriver(String driverId) {
        redisTemplate.opsForGeo().remove(GEO_KEY, driverId);
    }

    // Find up to maxResults driver IDs within radiusKm of the given point,
    // sorted by ascending distance (nearest first).
    public List<String> findNearbyDrivers(double longitude, double latitude) {
        Circle searchArea = new Circle(
                new Point(longitude, latitude),
                new Distance(radiusKm, Metrics.KILOMETERS));

        GeoResults<RedisGeoCommands.GeoLocation<String>> results =
                redisTemplate.opsForGeo().radius(
                        GEO_KEY,
                        searchArea,
                        RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs()
                                .includeDistance()
                                .sortAscending()
                                .limit(maxResults));

        if (results == null) return Collections.emptyList();

        return results.getContent().stream()
                .map(r -> r.getContent().getName())
                .collect(Collectors.toList());
    }

    // Fetch the distance (km) between two drivers or a driver and a point.
    public Double getDistance(String member1, String member2) {
        Distance dist = redisTemplate.opsForGeo()
                .distance(GEO_KEY, member1, member2, Metrics.KILOMETERS);
        return dist != null ? dist.getValue() : null;
    }
}
```

---

## File 11: RideService.java

```java
// apps/uber/rides-service/src/main/java/com/uber/rides/RideService.java
package com.uber.rides;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class RideService {

    @Autowired private RideRepository rideRepository;
    @Autowired private DriverRepository driverRepository;
    @Autowired private LocationService locationService;
    @Autowired private KafkaTemplate<String, String> kafkaTemplate;

    // Request a ride:
    // 1. Find nearest available drivers via Redis GEO (fast, ~0.5ms)
    // 2. Pick the nearest, update driver status in Postgres (ACID)
    // 3. Create ride record in Postgres
    // 4. Publish ride-events to Kafka
    //
    // @Transactional wraps steps 2+3: if either fails, both roll back.
    // This prevents a driver being marked BUSY without a corresponding ride record.
    @Transactional
    public Ride requestRide(Long userId, double pickupLat, double pickupLng) {
        // Find nearby available drivers by location
        List<String> nearbyDriverIds = locationService.findNearbyDrivers(pickupLng, pickupLat);

        if (nearbyDriverIds.isEmpty()) {
            throw new RuntimeException("No drivers available nearby");
        }

        // Find first available driver from the nearby list
        Driver matched = null;
        for (String driverIdStr : nearbyDriverIds) {
            Long driverId = Long.parseLong(driverIdStr);
            Optional<Driver> driverOpt = driverRepository.findById(driverId);
            if (driverOpt.isPresent() && "AVAILABLE".equals(driverOpt.get().getStatus())) {
                matched = driverOpt.get();
                break;
            }
        }

        if (matched == null) {
            throw new RuntimeException("No available drivers found in nearby results");
        }

        // Mark driver as busy (transactional — rolls back if ride creation fails)
        matched.setStatus("BUSY");
        driverRepository.save(matched);

        // Create ride record
        Ride ride = new Ride();
        ride.setUserId(userId);
        ride.setDriverId(matched.getId());
        ride.setPickupLat(pickupLat);
        ride.setPickupLng(pickupLng);
        ride.setStatus("MATCHED");
        // Simple fare estimate: base $3 + $1.50/km (real Uber uses surge pricing)
        ride.setFare(3.0);
        Ride saved = rideRepository.save(ride);

        // Publish event
        String event = String.format(
                "{\"rideId\":%d,\"userId\":%d,\"driverId\":%d,\"status\":\"%s\"}",
                saved.getId(), saved.getUserId(), saved.getDriverId(), saved.getStatus());
        kafkaTemplate.send("ride-events", String.valueOf(saved.getId()), event);

        return saved;
    }

    @Transactional
    public Ride updateStatus(Long rideId, String newStatus) {
        Ride ride = rideRepository.findById(rideId)
                .orElseThrow(() -> new RuntimeException("Ride not found: " + rideId));

        String oldStatus = ride.getStatus();
        ride.setStatus(newStatus);
        Ride updated = rideRepository.save(ride);

        // Free up driver when ride completes or is cancelled
        if ("COMPLETED".equals(newStatus) || "CANCELLED".equals(newStatus)) {
            driverRepository.findById(ride.getDriverId()).ifPresent(driver -> {
                driver.setStatus("AVAILABLE");
                driverRepository.save(driver);
                // Re-register driver location in Redis on next ping
            });
        }

        String event = String.format(
                "{\"rideId\":%d,\"status\":\"%s\",\"previousStatus\":\"%s\"}",
                updated.getId(), updated.getStatus(), oldStatus);
        kafkaTemplate.send("ride-events", String.valueOf(updated.getId()), event);

        return updated;
    }

    public List<Ride> getRidesByUser(Long userId) {
        // Citus: userId is the shard key → single-shard query, fast.
        return rideRepository.findByUserId(userId);
    }

    public Optional<Ride> getRide(Long rideId) {
        return rideRepository.findById(rideId);
    }
}
```

---

## File 12: DriverController.java

```java
// apps/uber/rides-service/src/main/java/com/uber/rides/DriverController.java
package com.uber.rides;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/rides/drivers")
public class DriverController {

    @Autowired private DriverRepository driverRepository;
    @Autowired private LocationService locationService;

    @PostMapping
    public ResponseEntity<Driver> register(@RequestBody Driver driver) {
        driver.setStatus("AVAILABLE");
        return ResponseEntity.status(201).body(driverRepository.save(driver));
    }

    @GetMapping
    public List<Driver> getAll() {
        return driverRepository.findAll();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Driver> getById(@PathVariable Long id) {
        return driverRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
```

---

## File 13: LocationController.java

```java
// apps/uber/rides-service/src/main/java/com/uber/rides/LocationController.java
package com.uber.rides;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/rides/drivers")
public class LocationController {

    @Autowired private LocationService locationService;

    // Called every 5 seconds by the driver app.
    // Body: { "longitude": -73.98, "latitude": 40.75 }
    // This is the highest-frequency endpoint in the entire system.
    // Every call hits Redis GEOADD (~0.1ms). Never hits Postgres.
    @PutMapping("/{driverId}/location")
    public ResponseEntity<Void> updateLocation(
            @PathVariable String driverId,
            @RequestBody Map<String, Double> coords) {

        Double lng = coords.get("longitude");
        Double lat = coords.get("latitude");
        if (lng == null || lat == null) {
            return ResponseEntity.badRequest().build();
        }
        locationService.updateLocation(driverId, lng, lat);
        return ResponseEntity.ok().build();
    }

    // Called by the rider app to preview available drivers before requesting.
    @GetMapping("/nearby")
    public ResponseEntity<List<String>> findNearby(
            @RequestParam double longitude,
            @RequestParam double latitude) {
        return ResponseEntity.ok(locationService.findNearbyDrivers(longitude, latitude));
    }
}
```

---

## File 14: RideController.java

```java
// apps/uber/rides-service/src/main/java/com/uber/rides/RideController.java
package com.uber.rides;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/rides/rides")
public class RideController {

    @Autowired private RideService rideService;

    // Request a ride. Body: { "userId": 1, "pickupLat": 40.75, "pickupLng": -73.98 }
    @PostMapping
    public ResponseEntity<Ride> requestRide(@RequestBody Map<String, Object> body) {
        Long userId = Long.valueOf(body.get("userId").toString());
        double pickupLat = Double.parseDouble(body.get("pickupLat").toString());
        double pickupLng = Double.parseDouble(body.get("pickupLng").toString());
        return ResponseEntity.status(201)
                .body(rideService.requestRide(userId, pickupLat, pickupLng));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Ride> getRide(@PathVariable Long id) {
        return rideService.getRide(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping
    public List<Ride> getRidesByUser(@RequestParam Long userId) {
        return rideService.getRidesByUser(userId);
    }

    // PATCH: update status. Body: { "status": "COMPLETED" }
    @PatchMapping("/{id}/status")
    public ResponseEntity<Ride> updateStatus(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        return ResponseEntity.ok(rideService.updateStatus(id, body.get("status")));
    }
}
```

---

## Verification

```bash
bazel build //apps/uber/rides-service:rides-service
./bazel-bin/apps/uber/rides-service/rides-service

# ── Register two drivers ───────────────────────────────────────────────────
curl -X POST http://localhost:8083/api/rides/drivers \
  -H "Content-Type: application/json" \
  -d '{"name": "Alice Driver", "vehiclePlate": "ABC-1234"}'

curl -X POST http://localhost:8083/api/rides/drivers \
  -H "Content-Type: application/json" \
  -d '{"name": "Bob Driver", "vehiclePlate": "XYZ-5678"}'

# ── Simulate drivers sending location pings ────────────────────────────────
# Driver 1 — near Times Square
curl -X PUT http://localhost:8083/api/rides/drivers/1/location \
  -H "Content-Type: application/json" \
  -d '{"longitude": -73.9857, "latitude": 40.7580}'

# Driver 2 — 3km away
curl -X PUT http://localhost:8083/api/rides/drivers/2/location \
  -H "Content-Type: application/json" \
  -d '{"longitude": -73.9532, "latitude": 40.7711}'

# ── Verify locations in Redis ──────────────────────────────────────────────
docker exec -it uber-redis redis-cli ZRANGE drivers:locations 0 -1 WITHSCORES

# ── Search for nearby drivers ──────────────────────────────────────────────
curl "http://localhost:8083/api/rides/drivers/nearby?longitude=-73.985&latitude=40.758"
# Expected: ["1", "2"] sorted by distance

# ── Request a ride ─────────────────────────────────────────────────────────
curl -X POST http://localhost:8083/api/rides/rides \
  -H "Content-Type: application/json" \
  -d '{"userId": 100, "pickupLat": 40.758, "pickupLng": -73.985}'
# Expected: ride object with status=MATCHED, driverId=1

# ── Verify driver is now BUSY in Postgres ──────────────────────────────────
docker exec -it uber-postgres psql -U uber -d uber -c "SELECT id, name, status FROM drivers;"

# ── Complete the ride ──────────────────────────────────────────────────────
curl -X PATCH http://localhost:8083/api/rides/rides/1/status \
  -H "Content-Type: application/json" \
  -d '{"status": "COMPLETED"}'
# Driver 1 should now be AVAILABLE again

# ── Check ride events in Kafka ─────────────────────────────────────────────
docker exec -it uber-kafka kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic ride-events \
  --from-beginning

# ── Verify Citus sharding ─────────────────────────────────────────────────
# Connect to Citus and check shard distribution
docker exec -it uber-postgres psql -U uber -d uber -c \
  "SELECT nodename, count(*) FROM citus_shards WHERE table_name = 'rides'::regclass GROUP BY nodename;"
```

Continue to `STEPS_4.md` for Phase 4 (Notification Worker) and Phase 5 (API Gateway).
