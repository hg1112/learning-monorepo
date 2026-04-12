# Design Patterns — Tier 2 & 3 (GoF Supporting)

Tier 2: commonly asked by strong LLD interviewers. Tier 3: specialized, appear in specific contexts.

← Back to [Design Patterns Index](design_patterns.md)

---

## Tier 2 — Commonly Asked

---

### 13. Template Method

**Intent:** Define the skeleton of an algorithm in a base class, letting subclasses fill in specific steps.

```java
abstract class TripProcessor {
    public final TripSummary process(Trip trip) {  // template method
        validateTrip(trip);                   // fixed
        double distance = calcRoute(trip);    // subclass-specific
        double fare     = calcFare(distance); // subclass-specific
        notifyParties(trip, fare);            // fixed
        return buildSummary(trip, fare);      // fixed
    }

    protected abstract double calcRoute(Trip trip);
    protected abstract double calcFare(double distanceKm);

    private void validateTrip(Trip t)              { /* ... */ }
    private void notifyParties(Trip t, double fare) { /* ... */ }
    private TripSummary buildSummary(Trip t, double f) { /* ... */ }
}

class UberXTripProcessor extends TripProcessor {
    protected double calcRoute(Trip t) { return osrmClient.getDistance(t); }
    protected double calcFare(double d) { return 1.50 + d * 0.90; }
}
```

**When to use:** Workflow steps are fixed but individual steps vary by subtype.

---

### 14. Mediator

**Intent:** Define an object that encapsulates how a set of objects interact.
Objects no longer refer to each other directly — they talk through the mediator.
Reduces N×N dependencies to N×1.

```
Without Mediator:     With Mediator:
  A ↔ B ↔ C            A → Mediator ← C
  ↕ ↕ ↕                      ↕
  D ↔ E ↔ F            B → Mediator ← D
  (N² connections)      (N connections)
```

```java
// Classic: chat room where users don't know each other
interface ChatMediator {
    void sendMessage(String msg, User sender);
    void addUser(User user);
}

class ChatRoom implements ChatMediator {
    private final List<User> users = new ArrayList<>();
    public void addUser(User u) { users.add(u); }
    public void sendMessage(String msg, User sender) {
        users.stream()
             .filter(u -> u != sender)
             .forEach(u -> u.receive(sender.getName() + ": " + msg));
    }
}

abstract class User {
    protected ChatMediator mediator;
    protected String name;
    public abstract void send(String msg);
    public abstract void receive(String msg);
}
class ChatUser extends User {
    public ChatUser(ChatMediator m, String name) { this.mediator = m; this.name = name; }
    public void send(String msg)    { mediator.sendMessage(msg, this); }
    public void receive(String msg) { System.out.println(name + " received: " + msg); }
}
```

**Distributed version — Event Bus as Mediator:**
```java
class EventBus {
    private final Map<String, List<Consumer<Event>>> handlers = new ConcurrentHashMap<>();

    public void subscribe(String eventType, Consumer<Event> handler) {
        handlers.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>()).add(handler);
    }
    public void publish(Event event) {
        handlers.getOrDefault(event.getType(), List.of()).forEach(h -> h.accept(event));
    }
}

// Services are decoupled — RideService never calls NotificationService directly
eventBus.subscribe("RideMatched", e -> notificationService.push(e));
eventBus.subscribe("RideMatched", e -> surgeEngine.recalculate(e));
eventBus.publish(new RideMatchedEvent(rideId, driverId));
```

**Real-world:** Chat rooms, GUI event systems, Guava EventBus, Kafka (distributed Mediator).
**vs Observer:** Mediator coordinates two-way interaction; Observer is one-way broadcast.

---

### 15. Composite

**Intent:** Compose objects into tree structures. Treat individual objects and compositions uniformly.

```java
// Used in Uber Eats — a menu item can be a single dish or a combo
interface MenuItem {
    double getPrice();
    void print(String indent);
}
class Dish implements MenuItem {
    private String name; private double price;
    public double getPrice() { return price; }
    public void print(String i) { System.out.println(i + name + " $" + price); }
}
class Combo implements MenuItem {
    private String name;
    private List<MenuItem> items = new ArrayList<>();
    public void add(MenuItem m)  { items.add(m); }
    public double getPrice()     { return items.stream().mapToDouble(MenuItem::getPrice).sum() * 0.9; }
    public void print(String i)  { System.out.println(i + name); items.forEach(m -> m.print(i + "  ")); }
}

// Build a menu tree — caller doesn't care if item is leaf or composite
MenuItem burgerCombo = new Combo("Burger Meal");
burgerCombo.add(new Dish("Burger", 8.99));
burgerCombo.add(new Dish("Fries",  2.99));
burgerCombo.add(new Dish("Drink",  1.99));
burgerCombo.getPrice();  // (8.99 + 2.99 + 1.99) × 0.9 = 12.57
```

**Uber backend:** Uber Eats menu hierarchy. Also used for permission trees (role → permission groups → individual permissions).

---

### 16. Bridge

**Intent:** Decouple an abstraction from its implementation so both can vary independently.
Without Bridge, M abstractions × N implementations = M×N subclasses.

```
Without Bridge:        With Bridge:
  Shape                  Shape (abstraction)     Renderer (impl interface)
  ├─ CircleOpenGL          ├─ Circle         ───►  ├─ OpenGLRenderer
  ├─ CircleVulkan          └─ Square               └─ VulkanRenderer
  ├─ SquareOpenGL
  └─ SquareVulkan        Bridge = composition instead of inheritance
```

```java
// Implementation interface (can vary independently)
interface NotificationSender {
    void send(String recipient, String message);
}
class PushNotificationSender implements NotificationSender {
    public void send(String token, String msg) { fcm.send(token, msg); }
}
class SMSSender implements NotificationSender {
    public void send(String phone, String msg) { twilioClient.send(phone, msg); }
}
class EmailSender implements NotificationSender {
    public void send(String email, String msg) { sesClient.send(email, msg); }
}

// Abstraction (can vary independently)
abstract class Notification {
    protected final NotificationSender sender;   // the bridge
    public Notification(NotificationSender sender) { this.sender = sender; }
    public abstract void notify(User user, String event);
}

class RideMatchedNotification extends Notification {
    public void notify(User user, String event) {
        sender.send(user.getContact(), "Your driver is 3 min away!");
    }
}
class PaymentNotification extends Notification {
    public void notify(User user, String event) {
        sender.send(user.getContact(), "Payment of $" + event + " processed.");
    }
}

// Runtime combination — no new subclass needed for each pairing
Notification n  = new RideMatchedNotification(new PushNotificationSender());
Notification n2 = new PaymentNotification(new SMSSender());
```

**When to use:** Two independent dimensions of variation — prefer composition over inheritance.
**Real-world:** JDBC (`Connection` = abstraction, driver = implementation). Logging (`Logger` + `Appender`).

---

### 17. Abstract Factory

**Intent:** Create families of related objects without specifying concrete classes.

```java
// Two families: SQL (production) and In-Memory (testing)
interface DatabaseFactory {
    RideRepository createRideRepo();
    DriverRepository createDriverRepo();
}

class PostgresFactory implements DatabaseFactory {
    public RideRepository createRideRepo()    { return new PostgresRideRepository(); }
    public DriverRepository createDriverRepo() { return new PostgresDriverRepository(); }
}

class InMemoryFactory implements DatabaseFactory {
    public RideRepository createRideRepo()    { return new InMemoryRideRepository(); }
    public DriverRepository createDriverRepo() { return new InMemoryDriverRepository(); }
}
```

**When to use:** Switch between families of related objects (prod DB vs test DB, different cloud providers).

---

### 18. Visitor

**Intent:** Add new operations to a class hierarchy without modifying the classes.
Uses double dispatch — which method runs depends on both the visitor type and element type.

```java
// Element hierarchy (stable — rarely changes)
interface RideComponent { void accept(RideVisitor visitor); }

class Trip      implements RideComponent {
    public double distanceKm; public int durationMin;
    public void accept(RideVisitor v) { v.visit(this); }
}
class Surcharge implements RideComponent {
    public String reason; public double amount;
    public void accept(RideVisitor v) { v.visit(this); }
}
class Discount  implements RideComponent {
    public String code; public double percentage;
    public void accept(RideVisitor v) { v.visit(this); }
}

// Visitor interface — one method per element type
interface RideVisitor {
    void visit(Trip t);
    void visit(Surcharge s);
    void visit(Discount d);
}

// Add operations by writing new visitors — zero changes to RideComponent classes
class FareCalculatorVisitor implements RideVisitor {
    private double total = 0;
    public void visit(Trip t)      { total += 1.50 + t.distanceKm * 0.90; }
    public void visit(Surcharge s) { total += s.amount; }
    public void visit(Discount d)  { total *= (1 - d.percentage / 100); }
    public double getTotal()       { return total; }
}

class ReceiptPrinterVisitor implements RideVisitor {
    public void visit(Trip t)      { System.out.printf("Trip: %.1fkm%n", t.distanceKm); }
    public void visit(Surcharge s) { System.out.printf("Surcharge(%s): $%.2f%n", s.reason, s.amount); }
    public void visit(Discount d)  { System.out.printf("Promo %s: -%.0f%%%n", d.code, d.percentage); }
}

// Double dispatch
List<RideComponent> components = List.of(trip, airportFee, promoDiscount);
FareCalculatorVisitor calc = new FareCalculatorVisitor();
components.forEach(c -> c.accept(calc));
System.out.printf("Total: $%.2f%n", calc.getTotal());
```

**When to use:** Element hierarchy is stable, operations on it change frequently.
**Real-world:** Java `Files.walkFileTree`, compiler AST passes, billing fare components.

---

### 19. Memento

**Intent:** Capture an object's internal state and store it externally so it can be
restored later — without exposing the object's internals.

```
Originator → creates Memento (snapshot) → stored by Caretaker
Originator ← restores from Memento      ← retrieved from Caretaker
```

```java
class RideEditor {                        // Originator
    private String pickup, dropoff, rideType;

    public Memento save() { return new Memento(pickup, dropoff, rideType); }
    public void restore(Memento m) {
        this.pickup = m.pickup; this.dropoff = m.dropoff; this.rideType = m.rideType;
    }

    public static class Memento {         // immutable snapshot
        private final String pickup, dropoff, rideType;
        private Memento(String p, String d, String r) {
            this.pickup = p; this.dropoff = d; this.rideType = r;
        }
    }
}

class UndoManager {                       // Caretaker
    private final Deque<RideEditor.Memento> history = new ArrayDeque<>();
    public void push(RideEditor.Memento m) { history.push(m); }
    public RideEditor.Memento pop()        { return history.pop(); }
    public boolean canUndo()               { return !history.isEmpty(); }
}

RideEditor editor = new RideEditor();
UndoManager undo  = new UndoManager();
editor.setPickup("Times Square");
undo.push(editor.save());               // snapshot
editor.setPickup("JFK Airport");
editor.restore(undo.pop());             // back to Times Square
```

**Event Sourcing is Memento at scale** — every state change stored as an immutable event;
replay events to rebuild any past state (see [patterns_distributed.md](patterns_distributed.md)).

**Real-world:** Undo/redo in editors, DB transaction savepoints, game checkpoints, Git commits.

---

## Tier 3 — Specialized

---

### 20. Flyweight

**Intent:** Share common (intrinsic) state between many objects to reduce memory. Only per-object (extrinsic) state differs.

```
Intrinsic state  = immutable, shared across instances (e.g., icon image data)
Extrinsic state  = unique per instance, passed in at runtime (e.g., position on screen)
```

```java
// Without Flyweight: 100,000 driver pins on a map, each holds a full icon image
// DriverPin { byte[] iconImage; int x; int y; }  // 50KB × 100K = 5 GB ❌

// With Flyweight: share the icon image
final class DriverPinIcon {                          // Flyweight — immutable, shared
    private final byte[] imageData;
    private final String vehicleType;

    private DriverPinIcon(String vehicleType, byte[] data) {
        this.vehicleType = vehicleType; this.imageData = data;
    }
    public void render(int x, int y, Graphics g) {
        g.drawImage(imageData, x, y);
    }
}

class DriverPinIconFactory {
    private static final Map<String, DriverPinIcon> cache = new HashMap<>();
    public static DriverPinIcon get(String vehicleType) {
        return cache.computeIfAbsent(vehicleType,
            t -> new DriverPinIcon(t, loadImage(t)));
    }
}

class DriverPin {
    private final DriverPinIcon icon;  // shared flyweight
    private int x, y;                 // extrinsic — unique per pin

    public DriverPin(String vehicleType, int x, int y) {
        this.icon = DriverPinIconFactory.get(vehicleType);
        this.x = x; this.y = y;
    }
    public void render(Graphics g) { icon.render(x, y, g); }
}
// 100,000 pins × 3 vehicle types = 3 icon objects, not 100,000
```

**Real-world:** Java `String` interning, `Integer.valueOf()` cache (−128 to 127), character objects in text editors, tile sprites in game maps.

---

### 21. Prototype

**Intent:** Clone an existing object instead of constructing from scratch.

```java
public class DriverProfile implements Cloneable {
    private String id;
    private VehicleInfo vehicle;

    @Override
    public DriverProfile clone() {
        try {
            DriverProfile copy = (DriverProfile) super.clone();
            copy.vehicle = this.vehicle.clone();  // deep copy mutable fields
            return copy;
        } catch (CloneNotSupportedException e) { throw new AssertionError(); }
    }
}
```

**When to use:** Creating many similar objects is expensive (e.g., template objects, cached snapshots).
