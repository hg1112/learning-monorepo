package challenges.lld.food_ordering;

import java.util.*;

/**
 * Design a Restaurant Food Ordering & Rating System (Zomato / Uber Eats)
 *
 * - Food items shared across restaurants (e.g. "Veg Burger" at McDonald's and BK)
 * - Users order food, rate orders
 * - Queries: top-rated restaurants overall; top-rated restaurants for a food item
 *
 * Pattern cross-reference (docs/lld/):
 *   - Observer (patterns_tier1.md #2): OrderService publishes ORDER_RATED events;
 *     RatingIndex observes and updates its sorted structures.
 *   - Strategy (patterns_tier1.md #1): RankingStrategy — pluggable (avg rating,
 *     weighted rating, recency-decayed). Implemented as plain average here.
 *   - Facade (patterns_tier1.md #12): FoodOrderingSystem is the facade over
 *     OrderService, RestaurantService, RatingService subsystems.
 *   - Builder (patterns_tier1.md #3): Order.Builder — orders have many optional fields.
 *
 * Data structure (docs/data_structures/treemap_treeset.md):
 *   - TreeMap<Double, Set<String>> in RatingIndex for O(log n) rank maintenance.
 *     Same pattern as Leaderboard but keyed on Double rating instead of Integer score.
 *
 * Key methods implemented:
 *   1. placeOrder()              — create an order for a restaurant
 *   2. rateOrder()               — record a rating and propagate to restaurant/food stats
 *   3. getTopRestaurants()       — top-K overall by avg rating
 *   4. getTopRestaurantsForFood() — top-K for a specific food item
 */

// ─── Domain ───────────────────────────────────────────────────────────────────

class FoodItem {
    final String foodId;
    final String name;

    FoodItem(String foodId, String name) { this.foodId = foodId; this.name = name; }
}

class MenuItem {
    final FoodItem foodItem;
    final double   price;

    MenuItem(FoodItem foodItem, double price) {
        this.foodItem = foodItem;
        this.price    = price;
    }
}

class Restaurant {
    final String                  restaurantId;
    final String                  name;
    final Map<String, MenuItem>   menu = new LinkedHashMap<>();  // foodId → MenuItem

    Restaurant(String restaurantId, String name) {
        this.restaurantId = restaurantId;
        this.name         = name;
    }

    void addMenuItem(FoodItem item, double price) {
        menu.put(item.foodId, new MenuItem(item, price));
    }

    boolean serves(String foodId) { return menu.containsKey(foodId); }
}

class OrderItem {
    final String foodId;
    final int    quantity;
    OrderItem(String foodId, int quantity) { this.foodId = foodId; this.quantity = quantity; }
}

class Order {
    private static int seq = 1;
    enum Status { PLACED, DELIVERED, CANCELLED }

    final String          orderId;
    final String          userId;
    final String          restaurantId;
    final List<OrderItem> items;
    Status                status  = Status.PLACED;
    Integer               rating  = null;   // 1–5, set after delivery

    Order(String userId, String restaurantId, List<OrderItem> items) {
        this.orderId      = "ORD-" + (seq++);
        this.userId       = userId;
        this.restaurantId = restaurantId;
        this.items        = Collections.unmodifiableList(new ArrayList<>(items));
    }
}

// ─── Rating Index ─────────────────────────────────────────────────────────────
//
// Maintains running average rating and a sorted TreeMap for top-K queries.
// Same structural pattern as ScoreIndex in Leaderboard.

class RatingStats {
    int    count = 0;
    double sum   = 0.0;

    void add(int rating) { count++; sum += rating; }

    double average() { return count == 0 ? 0.0 : sum / count; }
}

class RatingIndex {
    // rating (rounded to 4dp) → set of entity IDs at that rating
    private final TreeMap<Double, Set<String>> tree = new TreeMap<>();
    private final Map<String, RatingStats>     stats = new HashMap<>();

    void record(String entityId, int rating) {
        RatingStats s = stats.computeIfAbsent(entityId, k -> new RatingStats());
        double oldAvg = s.average();

        // Remove from old position if already indexed
        if (s.count > 0) {
            removeFromTree(entityId, round(oldAvg));
        }

        s.add(rating);
        double newAvg = s.average();
        tree.computeIfAbsent(round(newAvg), k -> new HashSet<>()).add(entityId);
    }

    List<String> topK(int k) {
        List<String> result = new ArrayList<>(k);
        for (Map.Entry<Double, Set<String>> e : tree.descendingMap().entrySet()) {
            for (String id : e.getValue()) {
                result.add(id);
                if (result.size() == k) return result;
            }
        }
        return result;
    }

    double getAverage(String entityId) {
        RatingStats s = stats.get(entityId);
        return s == null ? 0.0 : s.average();
    }

    private void removeFromTree(String entityId, double avg) {
        Set<String> bucket = tree.get(avg);
        if (bucket != null) {
            bucket.remove(entityId);
            if (bucket.isEmpty()) tree.remove(avg);
        }
    }

    private double round(double v) { return Math.round(v * 10000.0) / 10000.0; }
}

// ─── Food Ordering System (Facade) ───────────────────────────────────────────

public class FoodOrderingSystem {

    private final Map<String, FoodItem>   foodItems   = new HashMap<>();
    private final Map<String, Restaurant> restaurants = new HashMap<>();
    private final Map<String, Order>      orders      = new HashMap<>();

    // Index: foodId → RatingIndex (tracks restaurant ratings for that food)
    private final Map<String, RatingIndex> foodRatingIndex = new HashMap<>();
    // Overall restaurant rating index
    private final RatingIndex overallIndex = new RatingIndex();

    // ── Setup ─────────────────────────────────────────────────────────────────

    public FoodItem addFoodItem(String foodId, String name) {
        FoodItem item = new FoodItem(foodId, name);
        foodItems.put(foodId, item);
        return item;
    }

    public Restaurant addRestaurant(String restaurantId, String name) {
        Restaurant r = new Restaurant(restaurantId, name);
        restaurants.put(restaurantId, r);
        return r;
    }

    public void addMenuItem(String restaurantId, String foodId, double price) {
        Restaurant r = getRestaurant(restaurantId);
        FoodItem   f = getFoodItem(foodId);
        r.addMenuItem(f, price);
    }

    // ── 1. placeOrder ─────────────────────────────────────────────────────────
    /**
     * Create an order. Validates that the restaurant serves every food item.
     * O(items) validation.
     */
    public Order placeOrder(String userId, String restaurantId, List<OrderItem> items) {
        Restaurant r = getRestaurant(restaurantId);
        for (OrderItem item : items) {
            if (!r.serves(item.foodId))
                throw new IllegalArgumentException(
                    restaurantId + " does not serve food item: " + item.foodId);
        }
        Order order = new Order(userId, restaurantId, items);
        orders.put(order.orderId, order);
        return order;
    }

    // ── 2. rateOrder ─────────────────────────────────────────────────────────
    /**
     * Rate a delivered order (1–5 stars).
     *
     * Observer pattern: after recording the rating on the order, this method
     * propagates (publishes) the update to:
     *   - overallIndex:     overall restaurant ranking
     *   - foodRatingIndex:  per-food-item restaurant ranking
     *
     * O(items × log n) to update all food-specific indexes.
     */
    public void rateOrder(String orderId, int rating) {
        if (rating < 1 || rating > 5)
            throw new IllegalArgumentException("Rating must be 1–5");

        Order order = orders.get(orderId);
        if (order == null) throw new IllegalArgumentException("Unknown order: " + orderId);
        if (order.rating != null) throw new IllegalStateException("Already rated: " + orderId);

        order.rating = rating;
        order.status = Order.Status.DELIVERED;

        String restaurantId = order.restaurantId;

        // Update overall restaurant rating
        overallIndex.record(restaurantId, rating);

        // Update per-food rating index for each food item in the order
        for (OrderItem item : order.items) {
            foodRatingIndex
                .computeIfAbsent(item.foodId, k -> new RatingIndex())
                .record(restaurantId, rating);
        }
    }

    // ── 3. getTopRestaurants ─────────────────────────────────────────────────
    /**
     * Top-K restaurants by overall average rating.
     * O(k) scan from the tail of the sorted TreeMap.
     */
    public List<String> getTopRestaurants(int k) {
        return overallIndex.topK(k);
    }

    // ── 4. getTopRestaurantsForFood ───────────────────────────────────────────
    /**
     * Top-K restaurants by rating specifically for a food item.
     * Only restaurants that have been rated for this food are included.
     * O(k).
     */
    public List<String> getTopRestaurantsForFood(String foodId, int k) {
        RatingIndex idx = foodRatingIndex.get(foodId);
        if (idx == null) return Collections.emptyList();
        return idx.topK(k);
    }

    public double getRestaurantRating(String restaurantId) {
        return overallIndex.getAverage(restaurantId);
    }

    private Restaurant getRestaurant(String id) {
        Restaurant r = restaurants.get(id);
        if (r == null) throw new IllegalArgumentException("Unknown restaurant: " + id);
        return r;
    }

    private FoodItem getFoodItem(String id) {
        FoodItem f = foodItems.get(id);
        if (f == null) throw new IllegalArgumentException("Unknown food item: " + id);
        return f;
    }

    // ── Demo ──────────────────────────────────────────────────────────────────
    public static void main(String[] args) {
        FoodOrderingSystem sys = new FoodOrderingSystem();

        sys.addFoodItem("F1", "Veg Burger");
        sys.addFoodItem("F2", "Veg Spring Roll");
        sys.addFoodItem("F3", "Ice Cream");

        sys.addRestaurant("R1", "Burger King");
        sys.addRestaurant("R2", "McDonald's");
        sys.addRestaurant("R3", "Haldirams");

        sys.addMenuItem("R1", "F1", 120.0);
        sys.addMenuItem("R2", "F1", 110.0);
        sys.addMenuItem("R2", "F2", 80.0);
        sys.addMenuItem("R3", "F2", 75.0);
        sys.addMenuItem("R3", "F3", 60.0);

        // Place and rate orders
        Order o1 = sys.placeOrder("u1", "R1", List.of(new OrderItem("F1", 2)));
        Order o2 = sys.placeOrder("u2", "R2", List.of(new OrderItem("F1", 1)));
        Order o3 = sys.placeOrder("u3", "R2", List.of(new OrderItem("F2", 1)));
        Order o4 = sys.placeOrder("u4", "R3", List.of(new OrderItem("F2", 1), new OrderItem("F3", 1)));

        sys.rateOrder(o1.orderId, 4);   // R1 overall: 4.0
        sys.rateOrder(o2.orderId, 3);   // R2 overall: 3.0
        sys.rateOrder(o3.orderId, 5);   // R2 overall: (3+5)/2 = 4.0
        sys.rateOrder(o4.orderId, 5);   // R3 overall: 5.0

        System.out.println("Top 3 overall:        " + sys.getTopRestaurants(3));
        // → [R3(5.0), R1(4.0), R2(4.0)] or [R3, R2, R1] depending on tie-break

        System.out.println("Top 2 for Veg Burger: " + sys.getTopRestaurantsForFood("F1", 2));
        // → R1(4.0) > R2(3.0)  (only orders with F1 rated here)

        System.out.println("Top 2 for Spring Roll:" + sys.getTopRestaurantsForFood("F2", 2));
        // → R3(5.0) > R2(5.0)

        System.out.printf("R3 avg rating: %.2f%n", sys.getRestaurantRating("R3"));  // 5.00
    }
}
