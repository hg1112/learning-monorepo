package challenges.lld.leaderboard;

import java.util.*;

/**
 * Design a Leaderboard (fantasy-sports style)
 *
 * Each user has exactly one team with 1..N players.
 * Players receive positive/negative point updates during a live match.
 * User score = sum of all players' current scores on their team.
 * Support: addScore(), reset(), getTopK().
 *
 * Pattern cross-reference (docs/lld/):
 *   - Observer (patterns_tier1.md #2): PlayerRegistry publishes score updates;
 *     LeaderboardIndex is an observer that updates its sorted structure.
 *   - Facade (patterns_tier1.md #12): Leaderboard is the facade over PlayerRegistry
 *     + TeamRegistry + LeaderboardIndex subsystems.
 *   - Strategy (patterns_tier1.md #1): topK strategy could swap between a full sort
 *     and a min-heap. Implemented as min-heap (O(n log k)) here.
 *
 * Data structure (docs/data_structures/treemap_treeset.md):
 *   - TreeMap<Integer, Set<String>> scoreToUsers — sorted descending, O(log n) inserts
 *     and removals, O(k) top-K retrieval.
 *   - ConcurrentHashMap for player scores — O(1) point updates.
 *
 * Key methods implemented:
 *   1. addScore(playerId, delta)  — update player score, propagate to user score
 *   2. getTopK(k)                 — return top-K users by score in O(k log n) or O(k)
 *   3. reset(playerId)            — zero out a player's score
 */

// ─── Domain ───────────────────────────────────────────────────────────────────

class Player {
    final String playerId;
    int score;

    Player(String playerId) { this.playerId = playerId; }
}

class Team {
    final String       userId;
    final List<String> playerIds;    // player IDs on this team

    Team(String userId, List<String> playerIds) {
        this.userId    = userId;
        this.playerIds = Collections.unmodifiableList(new ArrayList<>(playerIds));
    }
}

// ─── Leaderboard Index ────────────────────────────────────────────────────────
//
// Maintains a TreeMap<score, Set<userId>> sorted ascending so that the *last*
// entry (highest score) is always O(1) away.  We navigate from the tail for top-K.
//
// Why TreeMap and not a heap?
//   A heap gives O(log n) insert but O(n) delete-by-key.
//   TreeMap gives O(log n) for both insert and delete-by-key because we can
//   look up the user's current score in O(1) and then remove from the tree in O(log n).

class ScoreIndex {

    // score → set of userIds at that score (ties handled by a HashSet)
    private final TreeMap<Integer, Set<String>> tree = new TreeMap<>();
    // userId → current score (for O(1) lookup before updating the tree)
    private final Map<String, Integer> userScore = new HashMap<>();

    void upsert(String userId, int newScore) {
        Integer old = userScore.get(userId);
        if (old != null) {                       // remove from old bucket
            Set<String> bucket = tree.get(old);
            if (bucket != null) {
                bucket.remove(userId);
                if (bucket.isEmpty()) tree.remove(old);
            }
        }
        userScore.put(userId, newScore);
        tree.computeIfAbsent(newScore, k -> new HashSet<>()).add(userId);
    }

    /** Returns top-K userIds by descending score.  O(k) with iterator from tail. */
    List<String> topK(int k) {
        List<String> result = new ArrayList<>(k);
        // NavigableMap.descendingMap() gives entries high → low
        for (Map.Entry<Integer, Set<String>> entry : tree.descendingMap().entrySet()) {
            for (String userId : entry.getValue()) {
                result.add(userId);
                if (result.size() == k) return result;
            }
        }
        return result;
    }

    int getScore(String userId) {
        return userScore.getOrDefault(userId, 0);
    }
}

// ─── Leaderboard Facade ───────────────────────────────────────────────────────

public class Leaderboard {

    private final Map<String, Player> players   = new HashMap<>();   // playerId → Player
    private final Map<String, Team>   teams      = new HashMap<>();   // userId  → Team
    // Reverse index: playerId → userId (who owns this player)
    private final Map<String, String> playerToUser = new HashMap<>();

    private final ScoreIndex index = new ScoreIndex();

    /** Register a user and their team of players. */
    public void registerTeam(String userId, List<String> playerIds) {
        teams.put(userId, new Team(userId, playerIds));
        for (String pid : playerIds) {
            players.putIfAbsent(pid, new Player(pid));
            playerToUser.put(pid, userId);
        }
        // Initialize this user's score in the index
        index.upsert(userId, computeUserScore(userId));
    }

    /**
     * 1. addScore() — add delta points to a player; propagate to their team's user score.
     *
     * O(log n) for the TreeMap update in ScoreIndex.
     */
    public void addScore(String playerId, int delta) {
        Player p = players.get(playerId);
        if (p == null) throw new IllegalArgumentException("Unknown player: " + playerId);

        p.score += delta;

        String userId = playerToUser.get(playerId);
        if (userId != null) {
            index.upsert(userId, computeUserScore(userId));
        }
    }

    /**
     * 2. getTopK() — return top-K users ranked by descending score.
     *
     * O(k) scan from the tail of the TreeMap.
     *
     * Alternative: min-heap of size k over all users → O(n log k).
     * TreeMap is faster here because updates keep it sorted incrementally.
     */
    public List<String> getTopK(int k) {
        return index.topK(k);
    }

    /**
     * 3. reset() — zero out a player's score; propagate to their user's score.
     *
     * O(log n).
     */
    public void reset(String playerId) {
        Player p = players.get(playerId);
        if (p == null) throw new IllegalArgumentException("Unknown player: " + playerId);

        p.score = 0;

        String userId = playerToUser.get(playerId);
        if (userId != null) {
            index.upsert(userId, computeUserScore(userId));
        }
    }

    public int getUserScore(String userId) {
        return index.getScore(userId);
    }

    // Sum of all players' scores on this user's team
    private int computeUserScore(String userId) {
        Team team = teams.get(userId);
        if (team == null) return 0;
        int total = 0;
        for (String pid : team.playerIds) {
            Player p = players.get(pid);
            if (p != null) total += p.score;
        }
        return total;
    }

    // ── Demo ──────────────────────────────────────────────────────────────────
    public static void main(String[] args) {
        Leaderboard lb = new Leaderboard();

        lb.registerTeam("alice", Arrays.asList("p1", "p2"));   // alice has p1, p2
        lb.registerTeam("bob",   Arrays.asList("p3"));          // bob   has p3
        lb.registerTeam("carol", Arrays.asList("p4", "p5"));

        lb.addScore("p1", 50);   // alice: 50
        lb.addScore("p2", 30);   // alice: 80
        lb.addScore("p3", 90);   // bob:   90
        lb.addScore("p4", 60);   // carol: 60
        lb.addScore("p5", 25);   // carol: 85

        // Ranking: bob(90) > carol(85) > alice(80)
        System.out.println("Top 3: " + lb.getTopK(3));   // [bob, carol, alice]

        lb.addScore("p1", 20);   // alice: 100 — now leads
        System.out.println("Top 2: " + lb.getTopK(2));   // [alice, bob]

        lb.reset("p1");          // alice: 30
        System.out.println("After reset top 3: " + lb.getTopK(3));   // [bob, carol, alice]
    }
}
