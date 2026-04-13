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