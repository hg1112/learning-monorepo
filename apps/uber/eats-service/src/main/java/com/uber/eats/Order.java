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