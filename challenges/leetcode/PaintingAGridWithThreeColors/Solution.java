import java.util.*;

// ════════════════════════════════════════════════════════════════════════════════
// BAD SOLUTION — original approach, wrong for any grid with m > 1
// ════════════════════════════════════════════════════════════════════════════════
//
// BUG 1 — Memoization + backtracking are incompatible
//   dp[r][c][clr] is cached on first visit, but the actual result depends on
//   which cells are already colored (the visited[] state). Two calls with the
//   same (r,c,clr) but different visited states return the same cached value.
//
// BUG 2 — DFS does not color all cells
//   The 4-direction DFS from (m-1,n-1) follows adjacency and can reach the
//   pre-seeded base case dp[0][0][i]=1 after visiting only a PATH through the
//   grid, leaving other cells uncolored. For a 2×2 grid the DFS visits
//   (1,1)→(0,1)→(0,0) and returns — cell (1,0) is never colored.
//
// BUG 3 — Wrong final answer
//   ans * 3 is coincidentally correct for m=1 (by color symmetry) but wrong
//   in general. For m=2,n=2 the code returns 24 instead of 18.
//
// ROOT CAUSE
//   dp[r][c][clr] is an unsound memoization key for a 2D grid. The count at
//   (r,c) with color clr depends on the colors of BOTH the left AND above
//   neighbors — information the key does not capture.

class SolutionBad {
    private final int M = 1000000007;

    public int colorTheGrid(int m, int n) {
        long[][][] dp = new long[m][n][3];
        for (int i = 0; i < m; i++)
            for (int j = 0; j < n; j++)
                for (int k = 0; k < 3; k++)
                    dp[i][j][k] = -1;

        int[][] visited = new int[m][n];
        visited[m - 1][n - 1] = 1;                  // BUG: pre-marks starting cell
        for (int i = 0; i < 3; i++) dp[0][0][i] = 1; // BUG: wrong base-case semantics

        long ans = f(m - 1, n - 1, 0, m, n, visited, dp); // BUG: only tries color 0
        ans = (((ans + ans) % M) + ans) % M;              // BUG: *3 is wrong in general
        return (int) ans;
    }

    private long f(int r, int c, int clr, int m, int n, int[][] visited, long[][][] dp) {
        if (dp[r][c][clr] != -1) return dp[r][c][clr]; // BUG: cache ignores visited state

        long result = 0;
        int[][] directions = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};
        for (int[] dir : directions) {
            int x = r + dir[0], y = c + dir[1];
            if (x < 0 || x >= m || y < 0 || y >= n) continue;
            if (visited[x][y] == 1) continue;
            for (int i = 0; i < 3; i++) {
                if (i == clr) continue;
                visited[x][y] = 1;                              // BUG: backtracking mixed
                result = (result + f(x, y, i, m, n, visited, dp)) % M; // with memoization
                visited[x][y] = 0;
            }
        }
        dp[r][c][clr] = result;
        return result;
    }
}


// ════════════════════════════════════════════════════════════════════════════════
// GOOD SOLUTION — memoized recursion over column patterns
// ════════════════════════════════════════════════════════════════════════════════
//
// KEY INSIGHT
//   Treat each column as a single unit — a "pattern" — which is a coloring of
//   all m cells in that column such that vertically adjacent cells differ.
//   For m ≤ 5 there are at most 3 × 2^(m-1) = 48 such patterns.
//
//   Two adjacent columns are "compatible" if no row r has the same color in
//   both columns. This single check encodes the entire horizontal constraint.
//
// RECURSIVE EQUATION
//   f(col, p) = Σ f(col+1, q)   for all q compatible with p
//
//   f(n-1, p) = 1               (last column, pattern already chosen)
//
//   answer   = Σ f(0, p)        over all valid patterns p
//
// WHY MEMOIZATION IS NOW SOUND
//   f(col, p) depends only on (col, p). Given those two values, the set of
//   valid completions is always the same — there is no hidden state.
//
// Time:  O(n × P²),  P ≤ 48  →  ~2.3M ops for n = 1000
// Space: O(n × P) memo + O(P²) compat

class Solution {
    private final int MOD = 1_000_000_007;
    private int m;
    private int[][] patterns;
    private int P;
    private List<List<Integer>> compat;
    private long[][] memo;

    public int colorTheGrid(int m, int n) {
        this.m = m;

        List<int[]> patList = new ArrayList<>();
        genPatterns(new int[m], 0, patList);
        patterns = patList.toArray(new int[0][]);
        P = patterns.length;

        compat = new ArrayList<>();
        for (int i = 0; i < P; i++) {
            compat.add(new ArrayList<>());
            for (int j = 0; j < P; j++) {
                boolean ok = true;
                for (int r = 0; r < m; r++)
                    if (patterns[i][r] == patterns[j][r]) { ok = false; break; }
                if (ok) compat.get(i).add(j);
            }
        }

        memo = new long[n][P];
        for (long[] row : memo) Arrays.fill(row, -1);

        long ans = 0;
        for (int p = 0; p < P; p++)
            ans = (ans + f(0, p, n)) % MOD;
        return (int) ans;
    }

    // Ways to color columns col..n-1 given column col uses pattern patIdx
    private long f(int col, int patIdx, int n) {
        if (col == n - 1) return 1;
        if (memo[col][patIdx] != -1) return memo[col][patIdx];
        long result = 0;
        for (int nextPat : compat.get(patIdx))
            result = (result + f(col + 1, nextPat, n)) % MOD;
        return memo[col][patIdx] = result;
    }

    private void genPatterns(int[] arr, int pos, List<int[]> res) {
        if (pos == m) { res.add(arr.clone()); return; }
        for (int c = 0; c < 3; c++) {
            if (pos > 0 && arr[pos - 1] == c) continue;
            arr[pos] = c;
            genPatterns(arr, pos + 1, res);
        }
    }
}


// ════════════════════════════════════════════════════════════════════════════════
// OPTIMAL SOLUTION — iterative column DP (same complexity, no recursion overhead)
// ════════════════════════════════════════════════════════════════════════════════
//
// dp[p] = number of valid grids where the current (rightmost filled) column
//         uses pattern p.
//
// Initialise all dp[p] = 1 (each pattern is valid alone for column 0).
// For each new column, push counts forward: every pattern i propagates its
// count to every compatible pattern j.
//
// Time:  O(n × P²),  P ≤ 48
// Space: O(P)  — two rolling arrays, no recursion stack

class SolutionOptimal {
    private final int MOD = 1_000_000_007;

    public int colorTheGrid(int m, int n) {
        List<int[]> patList = new ArrayList<>();
        genPatterns(new int[m], 0, m, patList);
        int P = patList.size();
        int[][] patterns = patList.toArray(new int[0][]);

        List<List<Integer>> compat = new ArrayList<>();
        for (int i = 0; i < P; i++) {
            compat.add(new ArrayList<>());
            for (int j = 0; j < P; j++) {
                boolean ok = true;
                for (int r = 0; r < m; r++)
                    if (patterns[i][r] == patterns[j][r]) { ok = false; break; }
                if (ok) compat.get(i).add(j);
            }
        }

        long[] dp = new long[P];
        Arrays.fill(dp, 1); // column 0: every valid pattern contributes 1 way

        for (int col = 1; col < n; col++) {
            long[] ndp = new long[P];
            for (int i = 0; i < P; i++) {
                if (dp[i] == 0) continue;
                for (int j : compat.get(i))
                    ndp[j] = (ndp[j] + dp[i]) % MOD;
            }
            dp = ndp;
        }

        long ans = 0;
        for (long v : dp) ans = (ans + v) % MOD;
        return (int) ans;
    }

    private void genPatterns(int[] arr, int pos, int m, List<int[]> res) {
        if (pos == m) { res.add(arr.clone()); return; }
        for (int c = 0; c < 3; c++) {
            if (pos > 0 && arr[pos - 1] == c) continue;
            arr[pos] = c;
            genPatterns(arr, pos + 1, m, res);
        }
    }
}
