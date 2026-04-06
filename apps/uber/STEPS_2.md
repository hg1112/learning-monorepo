# Phase 2 — Campaign Management (Port 8082)

Handles advertiser accounts, campaigns, and ad creatives. When a new ad is created, it publishes to `ad-created-events` — the ML Platform consumes this to compute the ad's tower embedding and upsert it into Qdrant (making it retrievable in Phase 6).

**Tech**: PostgreSQL + Citus (sharded by `advertiser_id`, ACID for billing), Redis (cache + atomic budget counters via Lua), MinIO (ad image creatives), Kafka (`ad-created-events`).

**Key learning**: The `CampaignAdService` interface has four implementations demonstrating four cache strategies. Controlled by `campaign.cache.strategy` in `application.properties`. Spring's `@ConditionalOnProperty` instantiates only the matching bean.

---

## Directory Structure

```
apps/uber/campaign-management/
├── BUILD.bazel
└── src/main/
    ├── java/com/uber/campaign/
    │   ├── CampaignManagementApplication.java
    │   ├── Advertiser.java
    │   ├── Campaign.java
    │   ├── Ad.java                             ← creative (title, description, imageUrl)
    │   ├── AdRepository.java
    │   ├── CampaignRepository.java
    │   ├── AdvertiserRepository.java
    │   ├── CampaignAdService.java              ← interface (4 implementations below)
    │   ├── CacheAsideCampaignAdService.java    ← strategy 1 (default)
    │   ├── ReadThroughCampaignAdService.java   ← strategy 2
    │   ├── WriteThroughCampaignAdService.java  ← strategy 3
    │   ├── WriteBehindCampaignAdService.java   ← strategy 4
    │   ├── BudgetService.java                  ← Redis Lua atomic budget enforcement
    │   ├── CampaignController.java
    │   ├── AdController.java
    │   ├── RedisConfig.java
    │   ├── CacheConfig.java
    │   ├── KafkaProducerConfig.java
    │   └── MinioConfig.java
    └── resources/
        └── application.properties
```

---

## File 1: BUILD.bazel

```python
# apps/uber/campaign-management/BUILD.bazel
load("@rules_java//java:defs.bzl", "java_binary", "java_library")

java_library(
    name = "campaign_lib",
    srcs = glob(["src/main/java/**/*.java"]),
    resources = glob(["src/main/resources/**"]),
    deps = [
        "@maven//:org_springframework_boot_spring_boot_starter_web",
        "@maven//:org_springframework_boot_spring_boot_starter_data_jpa",
        "@maven//:org_springframework_boot_spring_boot_starter_data_redis",
        "@maven//:org_springframework_boot_spring_boot_starter_cache",
        "@maven//:org_springframework_kafka_spring_kafka",
        "@maven//:org_postgresql_postgresql",
        "@maven//:com_amazonaws_aws_java_sdk_s3",
        "@maven//:com_amazonaws_aws_java_sdk_core",
        "@maven//:com_fasterxml_jackson_core_jackson_databind",
    ],
)

java_binary(
    name = "campaign-management",
    main_class = "com.uber.campaign.CampaignManagementApplication",
    runtime_deps = [":campaign_lib"],
)
```

---

## File 2: application.properties

```properties
# apps/uber/campaign-management/src/main/resources/application.properties
server.port=8082
spring.application.name=campaign-management

# PostgreSQL via Citus — connect to coordinator node.
# Citus routes writes to the correct worker shard transparently.
spring.datasource.url=jdbc:postgresql://localhost:5432/uber
spring.datasource.username=uber
spring.datasource.password=uber
spring.datasource.driver-class-name=org.postgresql.Driver

# JPA — validate schema (DDL created in setup.sh)
spring.jpa.hibernate.ddl-auto=validate
spring.jpa.show-sql=false
spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect

# Redis — cache + budget counters
spring.data.redis.host=localhost
spring.data.redis.port=6379

# Kafka — publishes ad-created-events
spring.kafka.bootstrap-servers=localhost:9092
spring.kafka.consumer.group-id=campaign-write-behind
spring.kafka.consumer.auto-offset-reset=earliest

# MinIO — ad creative images
minio.endpoint=http://localhost:9000
minio.accessKey=minioadmin
minio.secretKey=minioadmin
minio.bucket=ads-images

# Cache strategy — swap to change implementation:
#   cache-aside    (default)
#   read-through
#   write-through
#   write-behind
campaign.cache.strategy=cache-aside

logging.level.com.uber.campaign=DEBUG
```

---

## File 3: CampaignManagementApplication.java

```java
// apps/uber/campaign-management/src/main/java/com/uber/campaign/CampaignManagementApplication.java
package com.uber.campaign;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling   // enables the daily budget reset job in BudgetService
public class CampaignManagementApplication {
    public static void main(String[] args) {
        SpringApplication.run(CampaignManagementApplication.class, args);
    }
}
```

---

## File 4: Advertiser.java

```java
// apps/uber/campaign-management/src/main/java/com/uber/campaign/Advertiser.java
package com.uber.campaign;

import jakarta.persistence.*;

@Entity
@Table(name = "advertisers")
public class Advertiser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(name = "email", unique = true)
    private String email;

    // Getters and Setters
    public Long getId()               { return id; }
    public void setId(Long id)        { this.id = id; }
    public String getName()           { return name; }
    public void setName(String name)  { this.name = name; }
    public String getEmail()          { return email; }
    public void setEmail(String e)    { this.email = e; }
}
```

---

## File 5: Campaign.java

```java
// apps/uber/campaign-management/src/main/java/com/uber/campaign/Campaign.java
package com.uber.campaign;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.List;

@Entity
@Table(name = "campaigns")
public class Campaign {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Shard key in Citus. All campaigns from one advertiser on one shard.
    @Column(name = "advertiser_id", nullable = false)
    private Long advertiserId;

    @Column(nullable = false)
    private String name;

    // Fixed bid per 1000 impressions (CPM).
    // effectiveCPM in auction = fixedBidCpm × predictedCTR (from DCN ranker).
    @Column(name = "fixed_bid_cpm", nullable = false)
    private double fixedBidCpm;

    @Column(name = "daily_budget", nullable = false)
    private double dailyBudget;

    @Column(name = "total_budget")
    private double totalBudget;

    // JSON array of cuisine strings, e.g. ["Italian","Pizza"]
    // Used by Ad Serving Service to filter Qdrant ANN search.
    @Column(name = "target_cuisines")
    private String targetCuisines;   // comma-separated for simplicity

    @Column(name = "target_radius_km")
    private double targetRadiusKm = 10.0;

    // DRAFT | ACTIVE | PAUSED | PAUSED_BUDGET | ENDED
    // PAUSED_BUDGET: set by BudgetService when daily budget exhausted.
    // Reset to ACTIVE at midnight by BudgetService.resetDailyBudgets().
    @Column(nullable = false)
    private String status = "DRAFT";

    @Column(name = "start_date")
    private Instant startDate;

    @Column(name = "end_date")
    private Instant endDate;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    // Getters and Setters
    public Long getId()                          { return id; }
    public void setId(Long id)                   { this.id = id; }
    public Long getAdvertiserId()                { return advertiserId; }
    public void setAdvertiserId(Long aid)        { this.advertiserId = aid; }
    public String getName()                      { return name; }
    public void setName(String name)             { this.name = name; }
    public double getFixedBidCpm()               { return fixedBidCpm; }
    public void setFixedBidCpm(double bid)       { this.fixedBidCpm = bid; }
    public double getDailyBudget()               { return dailyBudget; }
    public void setDailyBudget(double budget)    { this.dailyBudget = budget; }
    public double getTotalBudget()               { return totalBudget; }
    public void setTotalBudget(double budget)    { this.totalBudget = budget; }
    public String getTargetCuisines()            { return targetCuisines; }
    public void setTargetCuisines(String t)      { this.targetCuisines = t; }
    public double getTargetRadiusKm()            { return targetRadiusKm; }
    public void setTargetRadiusKm(double r)      { this.targetRadiusKm = r; }
    public String getStatus()                    { return status; }
    public void setStatus(String status)         { this.status = status; }
    public Instant getStartDate()                { return startDate; }
    public void setStartDate(Instant d)          { this.startDate = d; }
    public Instant getEndDate()                  { return endDate; }
    public void setEndDate(Instant d)            { this.endDate = d; }
    public Instant getCreatedAt()                { return createdAt; }
    public void setCreatedAt(Instant t)          { this.createdAt = t; }
}
```

---

## File 6: Ad.java

```java
// apps/uber/campaign-management/src/main/java/com/uber/campaign/Ad.java
package com.uber.campaign;

import jakarta.persistence.*;
import java.io.Serializable;
import java.time.Instant;

// Creative — what the user actually sees. Belongs to a Campaign.
// Must implement Serializable so Redis can serialize/deserialize it as a cache value.
@Entity
@Table(name = "ads")
public class Ad implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Shard key — same as Campaign.advertiserId. Stored here so the Citus
    // shard router can avoid cross-shard lookups when fetching ads by campaign.
    @Column(name = "advertiser_id", nullable = false)
    private Long advertiserId;

    @Column(name = "campaign_id", nullable = false)
    private Long campaignId;

    @Column(nullable = false)
    private String title;

    private String description;

    // MinIO URL set after creative image is uploaded.
    @Column(name = "image_url")
    private String imageUrl;

    // ACTIVE | PAUSED
    @Column(nullable = false)
    private String status = "ACTIVE";

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    // Getters and Setters
    public Long getId()                          { return id; }
    public void setId(Long id)                   { this.id = id; }
    public Long getAdvertiserId()                { return advertiserId; }
    public void setAdvertiserId(Long aid)        { this.advertiserId = aid; }
    public Long getCampaignId()                  { return campaignId; }
    public void setCampaignId(Long cid)          { this.campaignId = cid; }
    public String getTitle()                     { return title; }
    public void setTitle(String title)           { this.title = title; }
    public String getDescription()               { return description; }
    public void setDescription(String desc)      { this.description = desc; }
    public String getImageUrl()                  { return imageUrl; }
    public void setImageUrl(String url)          { this.imageUrl = url; }
    public String getStatus()                    { return status; }
    public void setStatus(String status)         { this.status = status; }
    public Instant getCreatedAt()                { return createdAt; }
    public void setCreatedAt(Instant t)          { this.createdAt = t; }
}
```

---

## File 7: Repositories

```java
// apps/uber/campaign-management/src/main/java/com/uber/campaign/AdvertiserRepository.java
package com.uber.campaign;
import org.springframework.data.jpa.repository.JpaRepository;
public interface AdvertiserRepository extends JpaRepository<Advertiser, Long> {}

// apps/uber/campaign-management/src/main/java/com/uber/campaign/CampaignRepository.java
package com.uber.campaign;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface CampaignRepository extends JpaRepository<Campaign, Long> {
    List<Campaign> findByAdvertiserId(Long advertiserId);
    // Used by BudgetService midnight reset
    List<Campaign> findByStatus(String status);
}

// apps/uber/campaign-management/src/main/java/com/uber/campaign/AdRepository.java
package com.uber.campaign;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface AdRepository extends JpaRepository<Ad, Long> {
    List<Ad> findByCampaignId(Long campaignId);
    List<Ad> findByAdvertiserId(Long advertiserId);
}
```

---

## File 8: CampaignAdService.java (interface)

```java
// apps/uber/campaign-management/src/main/java/com/uber/campaign/CampaignAdService.java
package com.uber.campaign;

import java.util.Optional;

// One interface, four cache implementations.
// All controllers inject CampaignAdService — strategy is invisible to callers.
public interface CampaignAdService {
    Ad saveAd(Ad ad);
    Optional<Ad> getAd(Long id);
}
```

---

## File 9: CacheAsideCampaignAdService.java (Strategy 1 — default)

```java
// apps/uber/campaign-management/src/main/java/com/uber/campaign/CacheAsideCampaignAdService.java
package com.uber.campaign;

// STRATEGY 1: Cache-Aside (Lazy Loading)
//
// Read:  App checks Redis. Miss → App loads from DB → App fills Redis with TTL.
// Write: App updates DB → App deletes Redis key (next read will miss and reload).
//
// Failure mode: App crashes after DB write but before Redis DEL.
//   → Cache holds stale data until TTL (60min). Acceptable for ad creatives.
//   → NOT acceptable for budget counters (use BudgetService Lua script instead).
//
// This is the default (matchIfMissing = true).

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

@Service
@ConditionalOnProperty(name = "campaign.cache.strategy", havingValue = "cache-aside", matchIfMissing = true)
public class CacheAsideCampaignAdService implements CampaignAdService {

    @Autowired private AdRepository repository;
    @Autowired private RedisTemplate<String, Object> redisTemplate;
    @Autowired private KafkaTemplate<String, String> kafkaTemplate;

    private static final Duration TTL = Duration.ofMinutes(60);

    @Override
    public Ad saveAd(Ad ad) {
        boolean isNew = (ad.getId() == null);
        Ad saved = repository.save(ad);
        redisTemplate.delete(cacheKey(saved.getId()));     // invalidate on write

        if (isNew) {
            // Trigger ML Platform to compute ad_tower embedding → Qdrant upsert.
            // Ad Serving Service cannot retrieve this ad until the embedding exists.
            String event = String.format(
                    "{\"adId\":%d,\"campaignId\":%d,\"advertiserId\":%d,\"title\":\"%s\"}",
                    saved.getId(), saved.getCampaignId(), saved.getAdvertiserId(), saved.getTitle());
            kafkaTemplate.send("ad-created-events", String.valueOf(saved.getId()), event);
        }
        return saved;
    }

    @Override
    public Optional<Ad> getAd(Long id) {
        String key = cacheKey(id);
        Ad cached = (Ad) redisTemplate.opsForValue().get(key);
        if (cached != null) return Optional.of(cached);
        Optional<Ad> ad = repository.findById(id);
        ad.ifPresent(a -> redisTemplate.opsForValue().set(key, a, TTL));
        return ad;
    }

    private String cacheKey(Long id) { return "ad:" + id; }
}
```

---

## File 10: ReadThroughCampaignAdService.java (Strategy 2)

```java
// apps/uber/campaign-management/src/main/java/com/uber/campaign/ReadThroughCampaignAdService.java
package com.uber.campaign;

// STRATEGY 2: Read-Through
//
// Spring AOP intercepts @Cacheable method calls. On a miss, AOP calls the
// method body (DB query) and stores the result in Redis. The application never
// touches Redis directly for reads.
//
// Trade-off: Cold start stampede. On Redis restart, every ad misses simultaneously.
// Mitigation (not shown here): warm cache on startup by preloading active ads.

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@ConditionalOnProperty(name = "campaign.cache.strategy", havingValue = "read-through")
public class ReadThroughCampaignAdService implements CampaignAdService {

    @Autowired private AdRepository repository;
    @Autowired private KafkaTemplate<String, String> kafkaTemplate;

    @Override
    @CacheEvict(value = "ads", key = "#ad.id", condition = "#ad.id != null")
    public Ad saveAd(Ad ad) {
        boolean isNew = (ad.getId() == null);
        Ad saved = repository.save(ad);
        if (isNew) {
            String event = String.format(
                    "{\"adId\":%d,\"campaignId\":%d,\"advertiserId\":%d,\"title\":\"%s\"}",
                    saved.getId(), saved.getCampaignId(), saved.getAdvertiserId(), saved.getTitle());
            kafkaTemplate.send("ad-created-events", String.valueOf(saved.getId()), event);
        }
        return saved;
    }

    // Cache key = "ads::<id>". Spring fills this on miss by calling the method body.
    @Override
    @Cacheable(value = "ads", key = "#id")
    public Optional<Ad> getAd(Long id) {
        return repository.findById(id);
    }
}
```

---

## File 11: WriteThroughCampaignAdService.java (Strategy 3)

```java
// apps/uber/campaign-management/src/main/java/com/uber/campaign/WriteThroughCampaignAdService.java
package com.uber.campaign;

// STRATEGY 3: Write-Through
//
// Every write goes to DB AND cache synchronously before returning to caller.
// Cache is never stale. But: wasted memory — every ad ever written is cached,
// even ones never read again (paused campaigns, old creatives).
//
// Best for: read-heavy workloads where the hot set is well-defined.
// Worst for: write-heavy workloads or large ad catalogs with cold inventory.

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

@Service
@ConditionalOnProperty(name = "campaign.cache.strategy", havingValue = "write-through")
public class WriteThroughCampaignAdService implements CampaignAdService {

    @Autowired private AdRepository repository;
    @Autowired private RedisTemplate<String, Object> redisTemplate;
    @Autowired private KafkaTemplate<String, String> kafkaTemplate;

    private static final Duration TTL = Duration.ofMinutes(60);

    @Override
    public Ad saveAd(Ad ad) {
        boolean isNew = (ad.getId() == null);
        Ad saved = repository.save(ad);                                          // DB first
        redisTemplate.opsForValue().set(cacheKey(saved.getId()), saved, TTL);   // then cache
        if (isNew) {
            String event = String.format(
                    "{\"adId\":%d,\"campaignId\":%d,\"advertiserId\":%d,\"title\":\"%s\"}",
                    saved.getId(), saved.getCampaignId(), saved.getAdvertiserId(), saved.getTitle());
            kafkaTemplate.send("ad-created-events", String.valueOf(saved.getId()), event);
        }
        return saved;
    }

    @Override
    public Optional<Ad> getAd(Long id) {
        Ad cached = (Ad) redisTemplate.opsForValue().get(cacheKey(id));
        if (cached != null) return Optional.of(cached);
        return repository.findById(id);   // rarely hits after warm-up
    }

    private String cacheKey(Long id) { return "ad:" + id; }
}
```

---

## File 12: WriteBehindCampaignAdService.java (Strategy 4)

```java
// apps/uber/campaign-management/src/main/java/com/uber/campaign/WriteBehindCampaignAdService.java
package com.uber.campaign;

// STRATEGY 4: Write-Behind (Write-Back)
//
// On write: update Redis immediately (~0.1ms), publish to Kafka for async DB persist.
//           Return 200 to caller. DB is eventually consistent.
//
// Risk: Redis crash before Kafka consumer persists → write lost.
// Mitigation: Redis AOF (appendonly yes, appendfsync everysec in docker-compose).
//             Kafka consumer commits offset only AFTER successful DB write.
//
// Best for: high-frequency creative updates, impression counters.
// NOT for: budget deductions or billing (those need synchronous DB writes + acks=all).

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

@Service
@ConditionalOnProperty(name = "campaign.cache.strategy", havingValue = "write-behind")
public class WriteBehindCampaignAdService implements CampaignAdService {

    @Autowired private AdRepository repository;
    @Autowired private RedisTemplate<String, Object> redisTemplate;
    @Autowired private KafkaTemplate<String, String> kafkaTemplate;
    @Autowired private ObjectMapper objectMapper;

    private static final Duration TTL = Duration.ofHours(24);

    @Override
    public Ad saveAd(Ad ad) {
        if (ad.getId() == null) {
            // New ad — must persist synchronously to get DB-assigned ID.
            Ad saved = repository.save(ad);
            redisTemplate.opsForValue().set(cacheKey(saved.getId()), saved, TTL);
            // Publish ad-created-events for ML embedding
            String event = String.format(
                    "{\"adId\":%d,\"campaignId\":%d,\"advertiserId\":%d,\"title\":\"%s\"}",
                    saved.getId(), saved.getCampaignId(), saved.getAdvertiserId(), saved.getTitle());
            kafkaTemplate.send("ad-created-events", String.valueOf(saved.getId()), event);
            return saved;
        }
        // Existing ad update — write-behind: cache first, queue async DB persist.
        redisTemplate.opsForValue().set(cacheKey(ad.getId()), ad, TTL);
        try {
            kafkaTemplate.send("ad-write-behind",
                    String.valueOf(ad.getId()),
                    objectMapper.writeValueAsString(ad));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Serialization failed for write-behind", e);
        }
        return ad;
    }

    @Override
    public Optional<Ad> getAd(Long id) {
        Ad cached = (Ad) redisTemplate.opsForValue().get(cacheKey(id));
        if (cached != null) return Optional.of(cached);
        return repository.findById(id);
    }

    // Async DB flush — offset committed only after successful DB write.
    // If DB write fails, Kafka redelivers (no data loss).
    @KafkaListener(topics = "ad-write-behind", groupId = "campaign-write-behind")
    public void persistAdAsync(String json) {
        try {
            Ad ad = objectMapper.readValue(json, Ad.class);
            repository.save(ad);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to deserialize ad from write-behind topic", e);
        }
    }

    private String cacheKey(Long id) { return "ad:" + id; }
}
```

---

## File 13: BudgetService.java

```java
// apps/uber/campaign-management/src/main/java/com/uber/campaign/BudgetService.java
package com.uber.campaign;

// Atomic budget enforcement using Redis Lua scripting.
//
// WHY LUA SCRIPT?
// Redis is single-threaded. A Lua script runs atomically — no other command
// executes between INCR and the budget check. Without Lua, you'd need two
// round trips (GET + INCR), which creates a race: two Ad Servers could read
// "budget ok" simultaneously before either increments. The Lua script closes
// this window entirely.
//
// At 100K impressions/sec across 10K campaigns = 10 budget checks/sec/campaign.
// Redis single-thread throughput: ~100K commands/sec. With Lua batching: no issue.

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

@Service
public class BudgetService {

    @Autowired private RedisTemplate<String, Object> redisTemplate;
    @Autowired private CampaignRepository campaignRepository;

    // Lua script: atomically increment spend and check against budget.
    // Returns 1 if budget still available, 0 if exhausted.
    // KEYS[1] = "campaign:{id}:spend_today"   (current day's spend in cents)
    // ARGV[1] = amount to charge (clearing price in cents)
    // ARGV[2] = daily budget in cents
    private static final String BUDGET_LUA = """
            local current = redis.call('INCRBY', KEYS[1], ARGV[1])
            if current > tonumber(ARGV[2]) then
                return 0
            end
            return 1
            """;

    // Returns true if campaign has budget remaining and deducted successfully.
    // Called by Ad Serving Service after auction win.
    public boolean deductBudget(Long campaignId, double clearingPriceUsd) {
        Campaign campaign = campaignRepository.findById(campaignId).orElse(null);
        if (campaign == null) return false;

        String key = "campaign:" + campaignId + ":spend_today";
        long chargeCents = Math.round(clearingPriceUsd * 100);
        long budgetCents = Math.round(campaign.getDailyBudget() * 100);

        DefaultRedisScript<Long> script = new DefaultRedisScript<>(BUDGET_LUA, Long.class);
        Long result = redisTemplate.execute(script,
                Collections.singletonList(key),
                String.valueOf(chargeCents),
                String.valueOf(budgetCents));

        if (result == null || result == 0L) {
            // Budget exhausted — pause campaign until midnight reset.
            campaign.setStatus("PAUSED_BUDGET");
            campaignRepository.save(campaign);
            return false;
        }
        return true;
    }

    // Runs at midnight UTC. Resets all spend counters and restores paused campaigns.
    // At scale: use Flink 1-second tumbling windows instead (see SOTA in ads.md).
    @Scheduled(cron = "0 0 0 * * *")
    public void resetDailyBudgets() {
        // Delete all spend counter keys for today
        redisTemplate.delete(redisTemplate.keys("campaign:*:spend_today"));

        // Restore PAUSED_BUDGET campaigns to ACTIVE
        List<Campaign> paused = campaignRepository.findByStatus("PAUSED_BUDGET");
        paused.forEach(c -> c.setStatus("ACTIVE"));
        campaignRepository.saveAll(paused);
    }
}
```

---

## File 14: CampaignController.java

```java
// apps/uber/campaign-management/src/main/java/com/uber/campaign/CampaignController.java
package com.uber.campaign;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/campaigns")
public class CampaignController {

    @Autowired private CampaignRepository campaignRepository;

    @PostMapping
    public ResponseEntity<Campaign> create(@RequestBody Campaign campaign) {
        return ResponseEntity.status(201).body(campaignRepository.save(campaign));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Campaign> get(@PathVariable Long id) {
        return campaignRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping
    public List<Campaign> getByAdvertiser(@RequestParam Long advertiserId) {
        return campaignRepository.findByAdvertiserId(advertiserId);
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<Campaign> updateStatus(
            @PathVariable Long id, @RequestBody String status) {
        return campaignRepository.findById(id).map(c -> {
            c.setStatus(status.trim().replace("\"", ""));
            return ResponseEntity.ok(campaignRepository.save(c));
        }).orElse(ResponseEntity.notFound().build());
    }
}
```

---

## File 15: AdController.java

```java
// apps/uber/campaign-management/src/main/java/com/uber/campaign/AdController.java
package com.uber.campaign;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ads")
public class AdController {

    @Autowired private CampaignAdService adService;

    // Creating an ad publishes ad-created-events → ML Platform computes embedding.
    @PostMapping
    public ResponseEntity<Ad> create(@RequestBody Ad ad) {
        return ResponseEntity.status(201).body(adService.saveAd(ad));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Ad> get(@PathVariable Long id) {
        return adService.getAd(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}")
    public ResponseEntity<Ad> update(@PathVariable Long id, @RequestBody Ad ad) {
        ad.setId(id);
        return ResponseEntity.ok(adService.saveAd(ad));
    }
}
```

---

## File 16: RedisConfig.java

```java
// apps/uber/campaign-management/src/main/java/com/uber/campaign/RedisConfig.java
package com.uber.campaign;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Bean
    public RedisTemplate<String, Object> redisTemplate(
            RedisConnectionFactory factory, ObjectMapper objectMapper) {
        GenericJackson2JsonRedisSerializer json =
                new GenericJackson2JsonRedisSerializer(objectMapper);
        RedisTemplate<String, Object> t = new RedisTemplate<>();
        t.setConnectionFactory(factory);
        t.setKeySerializer(new StringRedisSerializer());
        t.setValueSerializer(json);
        t.setHashKeySerializer(new StringRedisSerializer());
        t.setHashValueSerializer(json);
        return t;
    }
}
```

---

## File 17: CacheConfig.java, KafkaProducerConfig.java, MinioConfig.java

```java
// apps/uber/campaign-management/src/main/java/com/uber/campaign/CacheConfig.java
package com.uber.campaign;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.*;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.*;
import java.time.Duration;

@Configuration
@EnableCaching
public class CacheConfig {
    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory factory) {
        RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(60))
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(new GenericJackson2JsonRedisSerializer()));
        return RedisCacheManager.builder(factory).cacheDefaults(config).build();
    }
}

// apps/uber/campaign-management/src/main/java/com/uber/campaign/KafkaProducerConfig.java
package com.uber.campaign;
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

// apps/uber/campaign-management/src/main/java/com/uber/campaign/MinioConfig.java
package com.uber.campaign;
import com.amazonaws.auth.*;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MinioConfig {
    @Value("${minio.endpoint}")  private String endpoint;
    @Value("${minio.accessKey}") private String accessKey;
    @Value("${minio.secretKey}") private String secretKey;

    @Bean
    public AmazonS3 s3Client() {
        return AmazonS3ClientBuilder.standard()
                .withEndpointConfiguration(new AwsClientBuilder.EndpointConfiguration(endpoint, "us-east-1"))
                .withCredentials(new AWSStaticCredentialsProvider(new BasicAWSCredentials(accessKey, secretKey)))
                .withPathStyleAccessEnabled(true)
                .build();
    }
}
```

---

## Verification

```bash
bazel build //apps/uber/campaign-management:campaign-management
./bazel-bin/apps/uber/campaign-management/campaign-management

# Create a campaign
curl -X POST http://localhost:8082/api/campaigns \
  -H "Content-Type: application/json" \
  -d '{"advertiserId":1,"name":"Summer Pizza","fixedBidCpm":2.50,"dailyBudget":100,"targetCuisines":"Italian,Pizza","status":"ACTIVE"}'

# Create an ad (triggers ad-created-events → ML Platform embeds it in Qdrant)
curl -X POST http://localhost:8082/api/ads \
  -H "Content-Type: application/json" \
  -d '{"advertiserId":1,"campaignId":1,"title":"Best Pizza in Town","status":"ACTIVE"}'

# Verify Kafka received ad-created-events
docker exec -it uber-kafka kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic ad-created-events --from-beginning

# Switch cache strategy in application.properties and observe behaviour:
#   campaign.cache.strategy=read-through
# Rebuild and hit GET /api/ads/1 twice — second hit has no DB query in logs.

# Observe Redis keys
docker exec -it uber-redis redis-cli
> KEYS campaign:*
> KEYS ad:*
```

Continue to `STEPS_3.md` for Phase 3 — Rides Service.
