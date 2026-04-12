import java.util.*;

// ════════════════════════════════════════════════════════════════════════════════
// BAD SOLUTION — broken bidirectional BFS (original attempt)
// ════════════════════════════════════════════════════════════════════════════════
//
// BUG 1 — HashMap<Integer,Integer> as visited set (x → y)
//   Stores only ONE y per x. If (2,1) and (2,3) are both visited,
//   the second put silently overwrites the first → cells re-explored.
//
// BUG 2 — Target frontier uses taxi-cab moves, not knight moves
//   directions = {{1,0},{0,1},{-1,0},{0,-1}} — completely wrong graph.
//   Both BFS frontiers must use knight moves for a valid bidirectional search.
//
// BUG 3 — Source and target frontiers are wired backwards
//   visitedFromSource records the TARGET (x,y) as a source cell.
//   distanceFromSource gets "0_0" written where distanceFromTarget should.
//   The meeting check therefore adds source+source instead of source+target.
//
// BUG 4 — Returns 0 when frontiers never meet
//   ans defaults to 0; if the corrupted frontiers never intersect the loop
//   empties the queue and returns 0 — wrong for any non-zero target.

class SolutionBad {
    public int minKnightMoves(int x, int y) {
        Queue<int[]> queue = new ArrayDeque<>();
        HashMap<Integer, Integer> visitedFromTarget = new HashMap<>(); // BUG 1
        HashMap<Integer, Integer> visitedFromSource = new HashMap<>(); // BUG 1
        HashMap<String, Integer> distanceFromSource = new HashMap<>();
        HashMap<String, Integer> distanceFromTarget = new HashMap<>();

        queue.offer(new int[]{x, y});
        queue.offer(new int[]{0, 0});

        if (x == 0 & y == 0) return 0;
        visitedFromSource.put(x, y);                    // BUG 3: TARGET recorded as source
        distanceFromSource.put(x + "_" + y, 0);
        visitedFromTarget.put(0, 0);
        distanceFromSource.put(0 + "_" + 0, 0);         // BUG 3: should be distanceFromTarget

        int ans = 0;
        while (!queue.isEmpty()) {
            int[] coordinates = queue.remove();
            x = coordinates[0];
            y = coordinates[1];
            String key = x + "_" + y;

            if ((visitedFromSource.containsKey(x) && visitedFromSource.get(x) == y) &&
                (visitedFromTarget.containsKey(x) && visitedFromTarget.get(x) == y)) {
                ans = distanceFromSource.get(key) + distanceFromTarget.get(key); // BUG 3
                break;
            }

            if (distanceFromSource.containsKey(key)) {
                int[][] directions = {{1,2},{2,1},{-1,2},{2,-1},{-2,-1},{-1,-2},{1,-2},{-2,1}};
                for (int[] d : directions) {
                    int i = x + d[0], j = y + d[1];
                    if (visitedFromSource.containsKey(i) && visitedFromSource.get(i) == j) continue;
                    distanceFromSource.put(i + "_" + j, distanceFromSource.get(key) + 1);
                    visitedFromSource.put(i, j);         // BUG 1: overwrites earlier y for same x
                    queue.offer(new int[]{i, j});
                }
            }
            if (distanceFromTarget.containsKey(key)) {
                int[][] directions = {{1,0},{0,1},{-1,0},{0,-1}}; // BUG 2: taxi-cab
                for (int[] d : directions) {
                    int i = x + d[0], j = y + d[1];
                    if (visitedFromTarget.containsKey(i) && visitedFromTarget.get(i) == j) continue;
                    distanceFromTarget.put(i + "_" + j, distanceFromTarget.get(key) + 1);
                    visitedFromTarget.put(i, j);
                    queue.offer(new int[]{i, j});
                }
            }
        }
        return ans;
    }
}


// ════════════════════════════════════════════════════════════════════════════════
// GOOD SOLUTION — BFS restricted to first quadrant + −1 padding
// ════════════════════════════════════════════════════════════════════════════════
//
// Fold to (|x|,|y|) by symmetry. Allow coordinates down to −1 because the
// shortest path to some first-quadrant targets briefly dips below zero.
// Example: (0,0)→(2,−1)→(1,1) is the optimal 2-move path to (1,1).
//
// BFS processes cells in non-decreasing distance order, so the first time
// we dequeue the target it is already the minimum.
//
// Time / Space: O((|x|+2) × (|y|+2))

class Solution {
    public int minKnightMoves(int x, int y) {
        x = Math.abs(x);
        y = Math.abs(y);

        int[][] dirs = {{1,2},{2,1},{-1,2},{-2,1},{1,-2},{2,-1},{-1,-2},{-2,-1}};
        Map<String, Integer> dist = new HashMap<>();
        Queue<int[]> queue = new LinkedList<>();
        queue.offer(new int[]{0, 0});
        dist.put("0,0", 0);

        while (!queue.isEmpty()) {
            int[] cur = queue.poll();
            int cx = cur[0], cy = cur[1];
            int d = dist.get(cx + "," + cy);
            if (cx == x && cy == y) return d;
            for (int[] dir : dirs) {
                int nx = cx + dir[0], ny = cy + dir[1];
                if (nx >= -1 && ny >= -1) {
                    String key = nx + "," + ny;
                    if (!dist.containsKey(key)) {
                        dist.put(key, d + 1);
                        queue.offer(new int[]{nx, ny});
                    }
                }
            }
        }
        return -1;
    }
}


// ════════════════════════════════════════════════════════════════════════════════
// BETTER SOLUTION — DFS with memoization (top-down DP)
// ════════════════════════════════════════════════════════════════════════════════
//
// KEY INSIGHT — only two predecessors matter
//   In the first octant (x ≥ y ≥ 0), of the 8 possible previous positions only
//   two move BOTH coordinates toward the origin:
//     (x−1, y−2)  and  (x−2, y−1)
//   Taking abs() of the result after subtraction re-folds any negative coordinate
//   back into the first quadrant (valid by symmetry).
//
// RECURRENCE
//   f(x, y) = 1 + min( f(|x−1|, |y−2|),  f(|x−2|, |y−1|) )
//
// BASE CASES — both needed to break circular dependencies
//   f(0, 0) = 0
//   f(x, y) = 2   when x + y == 2   (cells (2,0), (0,2), (1,1))
//
//   WHY x+y==2 → 2:
//     These cells are not reachable in 1 move from origin, but are reachable
//     in exactly 2 moves by going through a negative coordinate:
//       (0,0) → (2,−1) → (1,1)       [via y=−1]
//       (0,0) → (1, 2) → (2, 0)      [direct path using positive coords]
//     Without this base case f(1,1) would recurse into f(1,0) → f(0,2) →
//     f(2,0) → f(1,1): a cycle. The base case breaks it.
//
//   CRITICAL ERROR in naive DP: using f(1,0)=3 as the only base case gives
//   f(1,1) = 4, which is WRONG. The correct answer f(1,1) = 2 because the
//   optimal path passes through (2,−1) — outside the first quadrant.
//   The x+y==2 base case encodes this without explicitly tracking negatives.
//
// Time:  O(|x| × |y|)
// Space: O(|x| × |y|) memo

class SolutionDFS {
    private final Map<String, Integer> memo = new HashMap<>();

    public int minKnightMoves(int x, int y) {
        return f(Math.abs(x), Math.abs(y));
    }

    private int f(int x, int y) {
        // Always normalize to first octant at the start of every call
        x = Math.abs(x);
        y = Math.abs(y);
        if (x < y) { int t = x; x = y; y = t; }

        if (x == 0 && y == 0) return 0;
        if (x + y == 2) return 2;   // covers (2,0),(0,2),(1,1)

        String key = x + "," + y;
        if (memo.containsKey(key)) return memo.get(key);

        int result = 1 + Math.min(
            f(x - 1, y - 2),   // abs + normalize happens at next call's start
            f(x - 2, y - 1)
        );
        memo.put(key, result);
        return result;
    }
}


// ════════════════════════════════════════════════════════════════════════════════
// OPTIMAL SOLUTION — O(1) closed-form formula
// ════════════════════════════════════════════════════════════════════════════════
//
// After normalizing to x ≥ y ≥ 0, the board splits into two regions:
//
//   delta = x − y   (how far we are from the diagonal)
//
//   REGION 1 — near diagonal (y > delta, i.e. x < 2y)
//     The knight covers ground by repeating a 3-move block that advances (3,3)
//     net. Each block "saves" 2 steps vs moving purely along x.
//     answer = delta − 2 × floor((delta − y) / 3)
//
//   REGION 2 — near x-axis (y ≤ delta, i.e. x ≥ 2y)
//     The knight advances mostly along x in 4-move blocks gaining (4,0) net.
//     answer = delta − 2 × floor((delta − y) / 4)
//
// Both formulas break down for two special cells:
//   (1, 0) — formula gives 1, correct answer is 3
//   (2, 2) — formula gives 2, correct answer is 4
// These are patched with explicit guards.
//
// NOTE: the (delta−y)/3 branch needs float division because delta−y < 0 in
// that region, and Java integer division truncates toward zero (not floor).
//
// Time: O(1)   Space: O(1)

class SolutionFormula {
    public int minKnightMoves(int x, int y) {
        x = Math.abs(x);
        y = Math.abs(y);
        if (x < y) { int t = x; x = y; y = t; }   // ensure x >= y

        if (x == 1 && y == 0) return 3;
        if (x == 2 && y == 2) return 4;

        int delta = x - y;
        if (y > delta) {
            // near-diagonal region: period-3 blocks
            return (int)(delta - 2 * Math.floor((float)(delta - y) / 3));
        } else {
            // near-axis region: period-4 blocks
            return (int)(delta - 2 * Math.floor((delta - y) / 4));
        }
    }
}
