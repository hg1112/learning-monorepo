// apps/uber/eats-service/src/main/java/com/uber/eats/OrderRepository.java
package com.uber.eats;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface OrderRepository extends MongoRepository<Order, String> {

    List<Order> findByCustomerId(String customerId);
    List<Order> findByRestaurantId(String restaurantId);
}