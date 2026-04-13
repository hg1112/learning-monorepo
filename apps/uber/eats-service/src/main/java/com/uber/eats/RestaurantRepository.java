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