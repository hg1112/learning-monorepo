package com.demo.pagination;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

// Composite index on (created_at DESC, id DESC) makes keyset pagination O(log N)
// instead of the O(N) full scan that OFFSET forces on large tables.
@Entity
@Table(
    name = "products",
    indexes = {
        @Index(name = "idx_products_created_at_id", columnList = "created_at DESC, id DESC"),
        @Index(name = "idx_products_category", columnList = "category")
    }
)
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private String category;

    @Column(precision = 10, scale = 2)
    private BigDecimal price;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }

    // Getters and setters
    public Long getId()                             { return id; }
    public void setId(Long id)                      { this.id = id; }
    public String getName()                         { return name; }
    public void setName(String name)                { this.name = name; }
    public String getCategory()                     { return category; }
    public void setCategory(String category)        { this.category = category; }
    public BigDecimal getPrice()                    { return price; }
    public void setPrice(BigDecimal price)          { this.price = price; }
    public Instant getCreatedAt()                   { return createdAt; }
    public void setCreatedAt(Instant createdAt)     { this.createdAt = createdAt; }
}
