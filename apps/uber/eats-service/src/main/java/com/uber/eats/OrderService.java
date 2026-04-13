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