# Phase 6 — Ad Serving Service (Port 8089, gRPC 9091)

The ML-powered ad serving pipeline. Every feed load triggers this service. It runs three stages in sequence: **Retrieval → Ranking → Auction**, all within a 20ms budget.

**Why a separate service from Campaign Management?**
Campaign Management is CRUD — low QPS, needs ACID. Ad Serving is latency-critical — called on every feed load, needs <20ms P99. Different SLAs, different tech, different failure modes. Decoupling lets each scale and fail independently.

**Protocols:**
- Feed BFF → Ad Serving: **gRPC unary** (`GetFeaturedAd`). Binary encoding, ~0.5ms vs ~5ms JSON over HTTP.
- Ad Serving → ML Platform: **REST** (HTTP). Internal LAN call ~1ms. Acceptable for orchestration.
- Ad Serving → Kafka: async publish (never blocks the response path).

---

## Architecture

```
Feed Request (userId, lat, lng, cuisine)
       │
       ▼  Ad Serving Service (gRPC :9091)
       │
       ├─ Stage 1: RETRIEVAL  ──────────────────────────────────────────────────
       │   POST http://localhost:8090/api/ml/retrieve
       │     → ML Platform: user_tower embedding → Qdrant ANN top-100 ad IDs
       │
       ├─ Stage 2: RANKING (DCN v2)  ──────────────────────────────────────────
       │   POST http://localhost:8090/api/ml/rank  (100 candidates)
       │     → ML Platform: build feature matrix → dcn_ranker → CTR scores
       │     → Circuit breaker: if >15ms, fall back to fixedBidCpm ranking
       │
       └─ Stage 3: AUCTION  ────────────────────────────────────────────────────
           effectiveCPM = campaign.fixedBidCpm × predictedCTR
           Second-price winner (pays second-highest effectiveCPM + $0.01)
           → BudgetService: Redis Lua deduction
           → Kafka: ad-impression-events
```

---

## Proto Definitions

These live in the shared proto directory created in STEPS_5:

### apps/uber/proto/adserving.proto

```protobuf
syntax = "proto3";
package uber.adserving;
option java_package = "com.uber.adserving.grpc";
option java_outer_classname = "AdServingProto";

service AdServingService {
  // Called by Feed BFF on every feed load. Returns the winning ad.
  rpc GetFeaturedAd (AdRequest) returns (AdResponse);
}

message AdRequest {
  string user_id   = 1;
  double latitude  = 2;
  double longitude = 3;
  string cuisine   = 4;   // context: what restaurants the user is browsing
}

message AdResponse {
  int64  ad_id         = 1;
  string title         = 2;
  string description   = 3;
  string image_url     = 4;
  int64  campaign_id   = 5;
  double clearing_price = 6;   // what advertiser actually pays (second price)
  bool   fallback_used = 7;    // true if ML circuit breaker triggered
}
```

---

## Directory Structure

```
apps/uber/ad-serving-service/
├── BUILD.bazel
└── src/main/
    ├── java/com/uber/adserving/
    │   ├── AdServingApplication.java
    │   ├── AdServingGrpcService.java    ← gRPC server implementation
    │   ├── RetrievalClient.java         ← calls ML Platform /api/ml/retrieve
    │   ├── RankingClient.java           ← calls ML Platform /api/ml/rank + circuit breaker
    │   ├── AuctionEngine.java           ← second-price auction logic
    │   ├── BudgetClient.java            ← calls Campaign Mgmt /api/budget/deduct
    │   ├── CampaignClient.java          ← fetches campaign data for 100 candidates
    │   └── KafkaProducerConfig.java
    └── resources/
        └── application.properties
```

---

## File 1: BUILD.bazel

```python
# apps/uber/ad-serving-service/BUILD.bazel
load("@rules_java//java:defs.bzl", "java_binary", "java_library")

java_library(
    name = "adserving_lib",
    srcs = glob(["src/main/java/**/*.java"]),
    resources = glob(["src/main/resources/**"]),
    deps = [
        "@maven//:org_springframework_boot_spring_boot_starter_web",
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
    name = "ad-serving-service",
    main_class = "com.uber.adserving.AdServingApplication",
    runtime_deps = [":adserving_lib"],
)
```

---

## File 2: application.properties

```properties
# apps/uber/ad-serving-service/src/main/resources/application.properties
server.port=8089
spring.application.name=ad-serving-service

# gRPC server port — Feed BFF connects here
grpc.server.port=9091

# Downstream service URLs
ml.platform.url=http://localhost:8090
campaign.management.url=http://localhost:8082

# Kafka
spring.kafka.bootstrap-servers=localhost:9092

# Circuit breaker thresholds
ml.circuit-breaker.timeout-ms=15
ml.circuit-breaker.failure-threshold=5

logging.level.com.uber.adserving=DEBUG
```

---

## File 3: AdServingApplication.java

```java
// apps/uber/ad-serving-service/src/main/java/com/uber/adserving/AdServingApplication.java
package com.uber.adserving;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class AdServingApplication {
    public static void main(String[] args) {
        SpringApplication.run(AdServingApplication.class, args);
    }
}
```

---

## File 4: RetrievalClient.java

```java
// apps/uber/ad-serving-service/src/main/java/com/uber/adserving/RetrievalClient.java
package com.uber.adserving;

// Calls ML Platform Stage 1: user_tower embedding → Qdrant ANN → top-100 ad IDs.
//
// The ML Platform handles:
//   1. Fetch user features from Redis (recent cuisines, click history)
//   2. Run user_tower via Triton gRPC → 128-dim unit vector
//   3. Qdrant cosine ANN search → top-100 candidate ad IDs
//
// Why not call Qdrant directly from here?
// The ML Platform owns the feature engineering and model inference.
// Keeping that logic in one place means the Java service is model-agnostic.

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class RetrievalClient {

    @Autowired private RestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;

    @Value("${ml.platform.url}")
    private String mlUrl;

    // Returns list of candidate ad IDs (up to 100).
    public List<Long> retrieve(String userId, double lat, double lng, String cuisine) {
        Map<String, Object> request = Map.of(
                "user_id", userId,
                "latitude", lat,
                "longitude", lng,
                "cuisine", cuisine
        );

        String response = restTemplate.postForObject(
                mlUrl + "/api/ml/retrieve", request, String.class);

        List<Long> adIds = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode candidates = root.get("candidate_ad_ids");
            if (candidates != null && candidates.isArray()) {
                candidates.forEach(node -> adIds.add(node.asLong()));
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse retrieval response", e);
        }
        return adIds;
    }
}
```

---

## File 5: RankingClient.java

```java
// apps/uber/ad-serving-service/src/main/java/com/uber/adserving/RankingClient.java
package com.uber.adserving;

// Calls ML Platform Stage 2: DCN v2 → predicted CTR scores for each candidate.
//
// Circuit Breaker Pattern:
// ML inference can spike on model cold starts or GPU contention.
// If the call exceeds 15ms, we fall back to fixedBidCpm ordering.
// The fallback is safe — ads still serve, just without CTR personalization.
// Without circuit breaker: one slow ML inference blocks the gRPC response,
// causing Feed BFF timeout, which drops the ad slot entirely (lost revenue).
//
// State machine:
//   CLOSED (normal) → OPEN (after N failures) → HALF-OPEN (probe) → CLOSED
// Here we implement a simplified version with a hard timeout.

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class RankingClient {

    @Autowired private RestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;

    @Value("${ml.platform.url}")
    private String mlUrl;

    @Value("${ml.circuit-breaker.failure-threshold}")
    private int failureThreshold;

    // Simple failure counter. In production, use Resilience4j CircuitBreaker.
    private final AtomicInteger failures = new AtomicInteger(0);
    private volatile boolean circuitOpen = false;

    // Returns map of adId → predictedCTR. Falls back to empty map on failure.
    public Map<Long, Double> rank(List<Long> adIds, String userId, String cuisine) {
        if (circuitOpen) {
            return Map.of();   // circuit open → caller uses fixedBidCpm fallback
        }

        Map<String, Object> request = Map.of(
                "ad_ids", adIds,
                "user_id", userId,
                "cuisine", cuisine
        );

        try {
            String response = restTemplate.postForObject(
                    mlUrl + "/api/ml/rank", request, String.class);
            Map<Long, Double> scores = new HashMap<>();
            JsonNode root = objectMapper.readTree(response);
            JsonNode rankings = root.get("rankings");
            if (rankings != null) {
                rankings.fields().forEachRemaining(e ->
                        scores.put(Long.parseLong(e.getKey()), e.getValue().asDouble()));
            }
            failures.set(0);      // reset on success
            circuitOpen = false;
            return scores;
        } catch (Exception e) {
            int f = failures.incrementAndGet();
            if (f >= failureThreshold) {
                circuitOpen = true;  // open circuit — stop hitting ML Platform
            }
            return Map.of();   // fallback: empty → AuctionEngine uses fixedBidCpm
        }
    }
}
```

---

## File 6: CampaignClient.java

```java
// apps/uber/ad-serving-service/src/main/java/com/uber/adserving/CampaignClient.java
package com.uber.adserving;

// Fetches campaign data (fixedBidCpm, targetCuisines, status) for ad candidates.
// Used by AuctionEngine to compute effectiveCPM and filter inactive campaigns.
//
// Alternative: embed campaign data in Qdrant payload during ad-created-events.
// Then retrieval returns campaign data alongside ad IDs — one fewer REST call.
// Trade-off: Qdrant payload is eventually consistent with Campaign Mgmt DB.
// For learning purposes, we make a synchronous REST call for simplicity.

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class CampaignClient {

    @Autowired private RestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;

    @Value("${campaign.management.url}")
    private String campaignUrl;

    // Returns map of adId → CampaignInfo for the given ad IDs.
    public Map<Long, CampaignInfo> fetchCampaignInfo(List<Long> adIds) {
        Map<Long, CampaignInfo> result = new HashMap<>();
        // In production: batch endpoint. Here: individual calls for clarity.
        for (Long adId : adIds) {
            try {
                CampaignInfo info = restTemplate.getForObject(
                        campaignUrl + "/api/ads/" + adId + "/campaign-info",
                        CampaignInfo.class);
                if (info != null && "ACTIVE".equals(info.getStatus())) {
                    result.put(adId, info);
                }
            } catch (Exception ignored) {
                // Ad not found or campaign paused — skip from auction
            }
        }
        return result;
    }

    // Lightweight DTO — only what the auction needs.
    public static class CampaignInfo {
        private Long campaignId;
        private double fixedBidCpm;
        private String status;

        public Long getCampaignId()        { return campaignId; }
        public void setCampaignId(Long id) { this.campaignId = id; }
        public double getFixedBidCpm()     { return fixedBidCpm; }
        public void setFixedBidCpm(double b) { this.fixedBidCpm = b; }
        public String getStatus()          { return status; }
        public void setStatus(String s)    { this.status = s; }
    }
}
```

---

## File 7: AuctionEngine.java

```java
// apps/uber/ad-serving-service/src/main/java/com/uber/adserving/AuctionEngine.java
package com.uber.adserving;

// Second-Price (Vickrey) Auction on effectiveCPM.
//
// effectiveCPM = fixedBidCpm × predictedCTR
//
// Why multiply by CTR?
// Raw bid ranking lets a high-bidding irrelevant ad win every time.
// This degrades UX (users ignore irrelevant ads) and reduces actual revenue
// (low CTR → few clicks → advertiser gets no ROI → they churn).
// CTR-weighted ranking: a $1.00 bid at 5% CTR (eCPM=$0.05) beats
// a $0.80 bid at 3% CTR (eCPM=$0.024) — better UX, higher realized revenue.
//
// Second-price clearing:
// Winner pays second-highest eCPM / winner's CTR (normalized to per-CPM).
// Here simplified: winner pays second-highest fixedBidCpm + $0.01.
// Production: clearing price = second_eCPM / winner_ctr (preserves incentives).

import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class AuctionEngine {

    public record AuctionResult(
            long adId,
            long campaignId,
            double clearingPrice,
            boolean fallbackUsed
    ) {}

    // candidates: adId → CampaignInfo
    // ctrScores: adId → predictedCTR (may be empty if circuit breaker triggered)
    public Optional<AuctionResult> run(
            Map<Long, CampaignClient.CampaignInfo> candidates,
            Map<Long, Double> ctrScores) {

        if (candidates.isEmpty()) return Optional.empty();

        boolean fallback = ctrScores.isEmpty();

        // Compute effectiveCPM for each candidate
        List<Map.Entry<Long, Double>> ranked = new ArrayList<>();
        for (Map.Entry<Long, CampaignClient.CampaignInfo> e : candidates.entrySet()) {
            long adId = e.getKey();
            double bidCpm = e.getValue().getFixedBidCpm();
            double ctr = ctrScores.getOrDefault(adId, 1.0);  // fallback: ctr=1 (rank by raw bid)
            double eCpm = bidCpm * ctr;
            ranked.add(Map.entry(adId, eCpm));
        }

        // Sort descending by eCPM
        ranked.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

        long winnerId = ranked.get(0).getKey();
        CampaignClient.CampaignInfo winner = candidates.get(winnerId);

        // Second price: winner pays second-highest fixedBidCpm + $0.01
        double clearingPrice = ranked.size() > 1
                ? candidates.get(ranked.get(1).getKey()).getFixedBidCpm() + 0.01
                : winner.getFixedBidCpm();

        return Optional.of(new AuctionResult(
                winnerId, winner.getCampaignId(), clearingPrice, fallback));
    }
}
```

---

## File 8: AdServingGrpcService.java

```java
// apps/uber/ad-serving-service/src/main/java/com/uber/adserving/AdServingGrpcService.java
package com.uber.adserving;

// gRPC server implementation. Feed BFF calls GetFeaturedAd on every feed load.
//
// The 3-stage pipeline runs synchronously within the gRPC handler:
//   Retrieval → Ranking → Auction → Budget deduction → Kafka publish
//
// Total target latency: <20ms P99
//   Retrieval (ML Platform REST): ~5ms
//   Ranking   (ML Platform REST): ~8ms (or 0ms if circuit breaker open)
//   Auction   (local):            ~0.1ms
//   Budget    (Redis Lua):        ~0.5ms
//   Kafka publish (async):        ~1ms fire-and-forget
//   Total:                        ~15ms typical, ~20ms P99

import com.uber.adserving.grpc.AdRequest;
import com.uber.adserving.grpc.AdResponse;
import com.uber.adserving.grpc.AdServingServiceGrpc;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@GrpcService
public class AdServingGrpcService extends AdServingServiceGrpc.AdServingServiceImplBase {

    @Autowired private RetrievalClient retrievalClient;
    @Autowired private RankingClient rankingClient;
    @Autowired private CampaignClient campaignClient;
    @Autowired private AuctionEngine auctionEngine;
    @Autowired private KafkaTemplate<String, String> kafkaTemplate;

    @Override
    public void getFeaturedAd(AdRequest request, StreamObserver<AdResponse> responseObserver) {
        String userId  = request.getUserId();
        double lat     = request.getLatitude();
        double lng     = request.getLongitude();
        String cuisine = request.getCuisine();

        // Stage 1: Retrieval — top-100 candidate ad IDs
        List<Long> candidates = retrievalClient.retrieve(userId, lat, lng, cuisine);
        if (candidates.isEmpty()) {
            responseObserver.onNext(AdResponse.getDefaultInstance());
            responseObserver.onCompleted();
            return;
        }

        // Stage 2: Ranking — predicted CTR for each candidate
        Map<Long, Double> ctrScores = rankingClient.rank(candidates, userId, cuisine);

        // Fetch campaign data for eligible candidates
        Map<Long, CampaignClient.CampaignInfo> campaignInfo =
                campaignClient.fetchCampaignInfo(candidates);

        // Stage 3: Auction — second-price on effectiveCPM
        Optional<AuctionEngine.AuctionResult> result =
                auctionEngine.run(campaignInfo, ctrScores);

        if (result.isEmpty()) {
            responseObserver.onNext(AdResponse.getDefaultInstance());
            responseObserver.onCompleted();
            return;
        }

        AuctionEngine.AuctionResult winner = result.get();

        // Publish impression event (fire-and-forget — never blocks response)
        String event = String.format(
                "{\"adId\":%d,\"campaignId\":%d,\"userId\":\"%s\",\"clearingPrice\":%.4f,\"fallback\":%b}",
                winner.adId(), winner.campaignId(), userId, winner.clearingPrice(), winner.fallbackUsed());
        kafkaTemplate.send("ad-impression-events", String.valueOf(winner.adId()), event);

        // Build gRPC response
        // In production: also fetch ad title/imageUrl from Campaign Mgmt or cache
        AdResponse response = AdResponse.newBuilder()
                .setAdId(winner.adId())
                .setCampaignId(winner.campaignId())
                .setClearingPrice(winner.clearingPrice())
                .setFallbackUsed(winner.fallbackUsed())
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }
}
```

---

## File 9: KafkaProducerConfig.java

```java
// apps/uber/ad-serving-service/src/main/java/com/uber/adserving/KafkaProducerConfig.java
package com.uber.adserving;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.*;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaProducerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

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

## Verification

```bash
bazel build //apps/uber/ad-serving-service:ad-serving-service
./bazel-bin/apps/uber/ad-serving-service/ad-serving-service

# Test via gRPC (install grpcurl first: brew install grpcurl)
grpcurl -plaintext -d '{
  "user_id": "user-001",
  "latitude": 37.7749,
  "longitude": -122.4194,
  "cuisine": "Italian"
}' localhost:9091 uber.adserving.AdServingService/GetFeaturedAd

# Watch the pipeline in the ML Platform logs
# Watch Kafka: docker exec -it uber-kafka kafka-console-consumer.sh \
#   --bootstrap-server localhost:9092 --topic ad-impression-events --from-beginning

# Simulate circuit breaker: stop ML Platform, send a request
# → fallback_used=true in response, eCPM ranked by raw bid only
```

Continue to `STEPS_7.md` for Phase 7 — ML Platform.
