package com.demo.pagination;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

// Seeds 100 products on first startup (skipped if rows already exist).
// Each product gets a realistic name, a random category, a random price,
// and a createdAt spread across the past 30 days so cursor ordering is meaningful.
@Component
public class DataSeeder {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private static final String[] CATEGORIES = { "Electronics", "Clothing", "Food", "Sports", "Books" };

    private static final String[] NAMES = {
        "Wireless Headphones", "Running Shoes", "Protein Powder", "Java Concurrency Book",
        "Standing Desk", "Mechanical Keyboard", "USB-C Hub", "Trail Mix",
        "Basketball", "Yoga Mat", "Coffee Beans", "Notebook",
        "Water Bottle", "Backpack", "Monitor Stand", "LED Lamp",
        "Resistance Bands", "Instant Pot", "Air Purifier", "Smart Watch"
    };

    private final ProductRepository repo;

    public DataSeeder(ProductRepository repo) {
        this.repo = repo;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seed() {
        if (repo.count() > 0) {
            log.info("Products table already has data — skipping seed.");
            return;
        }

        Random rng = new Random(42);
        List<Product> products = new ArrayList<>(100);
        Instant now = Instant.now();

        for (int i = 0; i < 100; i++) {
            Product p = new Product();
            p.setName(NAMES[i % NAMES.length] + " #" + (i + 1));
            p.setCategory(CATEGORIES[rng.nextInt(CATEGORIES.length)]);
            // price: $1.00 – $999.99
            double price = 1 + rng.nextDouble() * 998;
            p.setPrice(BigDecimal.valueOf(price).setScale(2, RoundingMode.HALF_UP));
            // spread createdAt over the past 30 days
            long offsetMinutes = (long) (rng.nextDouble() * 30 * 24 * 60);
            p.setCreatedAt(now.minus(offsetMinutes, ChronoUnit.MINUTES));
            products.add(p);
        }

        repo.saveAll(products);
        log.info("Seeded {} products.", products.size());
    }
}
