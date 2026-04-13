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