# Phase 1 — Eats Service (Port 8081)

Handles restaurant catalogs, menu items, and order placement.

**Tech**: MongoDB (document store for menus), MinIO (image uploads), Kafka (order events).

**Why MongoDB here**: The menu is a tree — Restaurant → MenuItem. Fetching a restaurant's full menu from a normalized relational DB requires 3-5 JOINs. MongoDB stores the whole document; one read = one disk seek. At dinner rush (50K menu views/sec) this difference is critical. See `docs/system_design/uber/eats.md` for full analysis.

---

## Directory Structure

```
apps/uber/eats-service/
├── BUILD.bazel
└── src/main/
    ├── java/com/uber/eats/
    │   ├── EatsServiceApplication.java
    │   ├── MinioConfig.java
    │   ├── KafkaProducerConfig.java
    │   ├── MenuItem.java
    │   ├── Restaurant.java
    │   ├── Order.java
    │   ├── RestaurantRepository.java
    │   ├── OrderRepository.java
    │   ├── RestaurantService.java
    │   ├── OrderService.java
    │   ├── RestaurantController.java
    │   └── OrderController.java
    └── resources/
        └── application.properties
```

---

## File 1: BUILD.bazel

```python
# apps/uber/eats-service/BUILD.bazel
load("@rules_java//java:defs.bzl", "java_binary", "java_library")

java_library(
    name = "eats_lib",
    srcs = glob(["src/main/java/**/*.java"]),
    resources = glob(["src/main/resources/**"]),
    deps = [
        "@maven//:org_springframework_boot_spring_boot_starter_web",
        "@maven//:org_springframework_boot_spring_boot_starter_data_mongodb",
        "@maven//:org_springframework_kafka_spring_kafka",
        "@maven//:com_amazonaws_aws_java_sdk_s3",
        "@maven//:com_amazonaws_aws_java_sdk_core",
        "@maven//:com_fasterxml_jackson_core_jackson_databind",
    ],
)

java_binary(
    name = "eats-service",
    main_class = "com.uber.eats.EatsServiceApplication",
    runtime_deps = [":eats_lib"],
)
```

---

## File 2: application.properties

```properties
# apps/uber/eats-service/src/main/resources/application.properties
server.port=8081
spring.application.name=eats-service

# MongoDB — stores restaurants and orders as documents
spring.data.mongodb.uri=mongodb://localhost:27017/eats_db

# Kafka — publishes order events consumed by notification-worker
spring.kafka.bootstrap-servers=localhost:9092

# MinIO — S3-compatible object storage for menu/restaurant images
minio.endpoint=http://localhost:9000
minio.accessKey=minioadmin
minio.secretKey=minioadmin
minio.bucket=eats-images

# Logging
logging.level.com.uber.eats=DEBUG
```

---

## File 3: EatsServiceApplication.java

```java
// apps/uber/eats-service/src/main/java/com/uber/eats/EatsServiceApplication.java
package com.uber.eats;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class EatsServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(EatsServiceApplication.class, args);
    }
}
```

---

## File 4: MinioConfig.java

```java
// apps/uber/eats-service/src/main/java/com/uber/eats/MinioConfig.java
package com.uber.eats;

import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MinioConfig {

    @Value("${minio.endpoint}")
    private String endpoint;

    @Value("${minio.accessKey}")
    private String accessKey;

    @Value("${minio.secretKey}")
    private String secretKey;

    @Bean
    public AmazonS3 s3Client() {
        // pathStyleAccessEnabled=true is required for MinIO.
        // AWS S3 uses virtual-hosted-style (bucket.s3.amazonaws.com).
        // MinIO uses path-style (localhost:9000/bucket-name). Without this flag
        // the SDK tries DNS resolution on the bucket name and fails locally.
        return AmazonS3ClientBuilder.standard()
                .withEndpointConfiguration(
                        new AwsClientBuilder.EndpointConfiguration(endpoint, "us-east-1"))
                .withCredentials(
                        new AWSStaticCredentialsProvider(
                                new BasicAWSCredentials(accessKey, secretKey)))
                .withPathStyleAccessEnabled(true)
                .build();
    }
}
```

---

## File 5: KafkaProducerConfig.java

```java
// apps/uber/eats-service/src/main/java/com/uber/eats/KafkaProducerConfig.java
package com.uber.eats;

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
        // acks=1: wait for leader broker ack only. Good balance of
        // durability vs latency for order events. Use acks=all for billing.
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

## File 6: MenuItem.java

```java
// apps/uber/eats-service/src/main/java/com/uber/eats/MenuItem.java
package com.uber.eats;

// Embedded document inside Restaurant — not a separate MongoDB collection.
// This is the key MongoDB advantage: the full menu is one read, not a JOIN.
public class MenuItem {

    private String name;
    private double price;
    private String imageUrl;   // MinIO URL set after upload
    private String category;   // e.g. "Mains", "Drinks", "Desserts"

    public MenuItem() {}

    public MenuItem(String name, double price, String category) {
        this.name = name;
        this.price = price;
        this.category = category;
    }

    // Getters and Setters
    public String getName()              { return name; }
    public void setName(String name)     { this.name = name; }
    public double getPrice()             { return price; }
    public void setPrice(double price)   { this.price = price; }
    public String getImageUrl()          { return imageUrl; }
    public void setImageUrl(String url)  { this.imageUrl = url; }
    public String getCategory()          { return category; }
    public void setCategory(String cat)  { this.category = cat; }
}
```

---

## File 7: Restaurant.java

```java
// apps/uber/eats-service/src/main/java/com/uber/eats/Restaurant.java
package com.uber.eats;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.ArrayList;
import java.util.List;

// @Document maps this class to a MongoDB collection.
// The entire menu (List<MenuItem>) is stored inline — no foreign key, no JOIN.
@Document(collection = "restaurants")
public class Restaurant {

    @Id
    private String id;       // MongoDB generates ObjectId strings

    private String name;
    private String cuisine;  // used by Ads service auction (targetCuisine matching)
    private String address;
    private String imageUrl; // MinIO URL for restaurant hero image
    private List<MenuItem> menu = new ArrayList<>();

    // Getters and Setters
    public String getId()                        { return id; }
    public void setId(String id)                 { this.id = id; }
    public String getName()                      { return name; }
    public void setName(String name)             { this.name = name; }
    public String getCuisine()                   { return cuisine; }
    public void setCuisine(String cuisine)       { this.cuisine = cuisine; }
    public String getAddress()                   { return address; }
    public void setAddress(String address)       { this.address = address; }
    public String getImageUrl()                  { return imageUrl; }
    public void setImageUrl(String url)          { this.imageUrl = url; }
    public List<MenuItem> getMenu()              { return menu; }
    public void setMenu(List<MenuItem> menu)     { this.menu = menu; }
}
```

---

## File 8: Order.java

```java
// apps/uber/eats-service/src/main/java/com/uber/eats/Order.java
package com.uber.eats;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

@Document(collection = "orders")
public class Order {

    @Id
    private String id;

    private String restaurantId;
    private String customerId;
    private List<MenuItem> items;   // snapshot of items at order time (prices may change)
    private String status;          // PREPARING | OUT_FOR_DELIVERY | DELIVERED | CANCELLED
    private double totalAmount;
    private Instant createdAt = Instant.now();

    // Getters and Setters
    public String getId()                          { return id; }
    public void setId(String id)                   { this.id = id; }
    public String getRestaurantId()                { return restaurantId; }
    public void setRestaurantId(String rid)        { this.restaurantId = rid; }
    public String getCustomerId()                  { return customerId; }
    public void setCustomerId(String cid)          { this.customerId = cid; }
    public List<MenuItem> getItems()               { return items; }
    public void setItems(List<MenuItem> items)     { this.items = items; }
    public String getStatus()                      { return status; }
    public void setStatus(String status)           { this.status = status; }
    public double getTotalAmount()                 { return totalAmount; }
    public void setTotalAmount(double total)       { this.totalAmount = total; }
    public Instant getCreatedAt()                  { return createdAt; }
    public void setCreatedAt(Instant t)            { this.createdAt = t; }
}
```

---

## File 9: RestaurantRepository.java

```java
// apps/uber/eats-service/src/main/java/com/uber/eats/RestaurantRepository.java
package com.uber.eats;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

// Spring Data generates all implementation at startup — no code needed.
// MongoRepository<T, ID>: T = document type, ID = @Id field type.
public interface RestaurantRepository extends MongoRepository<Restaurant, String> {

    // Spring Data derives the query from the method name:
    // "findBy" + "Cuisine" → db.restaurants.find({ cuisine: value })
    List<Restaurant> findByCuisine(String cuisine);
}
```

---

## File 10: OrderRepository.java

```java
// apps/uber/eats-service/src/main/java/com/uber/eats/OrderRepository.java
package com.uber.eats;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface OrderRepository extends MongoRepository<Order, String> {

    List<Order> findByCustomerId(String customerId);
    List<Order> findByRestaurantId(String restaurantId);
}
```

---

## File 11: RestaurantService.java

```java
// apps/uber/eats-service/src/main/java/com/uber/eats/RestaurantService.java
package com.uber.eats;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.ObjectMetadata;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class RestaurantService {

    @Autowired
    private RestaurantRepository repository;

    @Autowired
    private AmazonS3 s3Client;

    @Value("${minio.bucket}")
    private String bucketName;

    public Restaurant create(Restaurant restaurant) {
        return repository.save(restaurant);
    }

    public List<Restaurant> getAll() {
        return repository.findAll();
    }

    public Optional<Restaurant> getById(String id) {
        return repository.findById(id);
    }

    public List<Restaurant> getByCuisine(String cuisine) {
        return repository.findByCuisine(cuisine);
    }

    // Upload an image to MinIO and return the public URL.
    // The returned URL is stored in Restaurant.imageUrl or MenuItem.imageUrl.
    // Pattern: app uploads once → stores URL → mobile clients download
    // directly from MinIO without going through this service. This prevents
    // the app server from becoming a bandwidth bottleneck.
    public String uploadImage(MultipartFile file) throws IOException {
        ensureBucketExists();

        String fileName = UUID.randomUUID() + "-" + file.getOriginalFilename();
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentType(file.getContentType());
        metadata.setContentLength(file.getSize());

        s3Client.putObject(bucketName, fileName, file.getInputStream(), metadata);
        return s3Client.getUrl(bucketName, fileName).toString();
    }

    private void ensureBucketExists() {
        if (!s3Client.doesBucketExistV2(bucketName)) {
            s3Client.createBucket(bucketName);
        }
    }
}
```

---

## File 12: OrderService.java

```java
// apps/uber/eats-service/src/main/java/com/uber/eats/OrderService.java
package com.uber.eats;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class OrderService {

    @Autowired
    private OrderRepository repository;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    // Async checkout pattern:
    // 1. Save order to MongoDB (fast, ~2ms)
    // 2. Publish event to Kafka (fast, ~1ms)
    // 3. Return 201 immediately — do NOT wait for restaurant notification
    //
    // The Notification Worker picks up "order-events" and handles restaurant
    // notification independently. If it's down, messages queue in Kafka (7-day
    // retention) and are processed when it restarts — zero order data loss.
    public Order placeOrder(Order order) {
        order.setStatus("PREPARING");

        double total = 0;
        if (order.getItems() != null) {
            total = order.getItems().stream()
                    .mapToDouble(MenuItem::getPrice)
                    .sum();
        }
        order.setTotalAmount(total);

        Order saved = repository.save(order);

        // Key = orderId (Kafka uses this to route to a partition, ensuring
        // all events for one order go to the same partition in order).
        String event = String.format(
                "{\"orderId\":\"%s\",\"customerId\":\"%s\",\"status\":\"%s\",\"total\":%.2f}",
                saved.getId(), saved.getCustomerId(), saved.getStatus(), saved.getTotalAmount());
        kafkaTemplate.send("order-events", saved.getId(), event);

        return saved;
    }

    public Optional<Order> getById(String id) {
        return repository.findById(id);
    }

    public Order updateStatus(String id, String newStatus) {
        Order order = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("Order not found: " + id));
        order.setStatus(newStatus);
        Order updated = repository.save(order);

        String event = String.format(
                "{\"orderId\":\"%s\",\"status\":\"%s\"}",
                updated.getId(), updated.getStatus());
        kafkaTemplate.send("order-events", updated.getId(), event);

        return updated;
    }
}
```

---

## File 13: RestaurantController.java

```java
// apps/uber/eats-service/src/main/java/com/uber/eats/RestaurantController.java
package com.uber.eats;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/eats/restaurants")
public class RestaurantController {

    @Autowired
    private RestaurantService service;

    @PostMapping
    public ResponseEntity<Restaurant> create(@RequestBody Restaurant restaurant) {
        return ResponseEntity.status(201).body(service.create(restaurant));
    }

    @GetMapping
    public List<Restaurant> getAll(@RequestParam(required = false) String cuisine) {
        if (cuisine != null) return service.getByCuisine(cuisine);
        return service.getAll();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Restaurant> getById(@PathVariable String id) {
        return service.getById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // Upload a restaurant hero image or a menu item image.
    // Returns the MinIO URL — store it in the restaurant/menu item record.
    @PostMapping("/{id}/images")
    public ResponseEntity<String> uploadImage(
            @PathVariable String id,
            @RequestParam("file") MultipartFile file) throws IOException {
        String url = service.uploadImage(file);
        return ResponseEntity.ok(url);
    }
}
```

---

## File 14: OrderController.java

```java
// apps/uber/eats-service/src/main/java/com/uber/eats/OrderController.java
package com.uber.eats;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/eats/orders")
public class OrderController {

    @Autowired
    private OrderService service;

    @PostMapping
    public ResponseEntity<Order> placeOrder(@RequestBody Order order) {
        return ResponseEntity.status(201).body(service.placeOrder(order));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Order> getOrder(@PathVariable String id) {
        return service.getById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // PATCH is correct here: partial update (just the status field).
    // Body: plain string, e.g. "OUT_FOR_DELIVERY"
    @PatchMapping("/{id}/status")
    public ResponseEntity<Order> updateStatus(
            @PathVariable String id,
            @RequestBody String status) {
        return ResponseEntity.ok(service.updateStatus(id, status.trim().replace("\"", "")));
    }
}
```

---

## Verification

```bash
# Build
bazel build //apps/uber/eats-service:eats-service

# Run
./bazel-bin/apps/uber/eats-service/eats-service

# ── Test 1: Create a restaurant ────────────────────────────────────────────
curl -X POST http://localhost:8081/api/eats/restaurants \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Napoli Pizza",
    "cuisine": "Italian",
    "address": "123 Main St",
    "menu": [
      {"name": "Margherita", "price": 12.99, "category": "Pizza"},
      {"name": "Tiramisu",   "price": 6.50,  "category": "Dessert"}
    ]
  }'

# ── Test 2: Get all restaurants ────────────────────────────────────────────
curl http://localhost:8081/api/eats/restaurants

# ── Test 3: Place an order (use restaurantId from Test 1 response) ─────────
curl -X POST http://localhost:8081/api/eats/orders \
  -H "Content-Type: application/json" \
  -d '{
    "restaurantId": "<id-from-test-1>",
    "customerId": "customer-001",
    "items": [
      {"name": "Margherita", "price": 12.99, "category": "Pizza"}
    ]
  }'

# ── Test 4: Check Kafka received the event ─────────────────────────────────
docker exec -it uber-kafka kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic order-events \
  --from-beginning

# ── Test 5: Update order status ────────────────────────────────────────────
curl -X PATCH http://localhost:8081/api/eats/orders/<order-id>/status \
  -H "Content-Type: application/json" \
  -d '"OUT_FOR_DELIVERY"'
```

Continue to `STEPS_2.md` for Phase 2 — Ads Service.
