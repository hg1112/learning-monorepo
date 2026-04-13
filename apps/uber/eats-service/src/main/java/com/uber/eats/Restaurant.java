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