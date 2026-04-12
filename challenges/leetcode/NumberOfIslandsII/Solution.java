import java.util.*;

/**
 * LeetCode 305: Number of Islands II
 * 
 * Optimized for memory efficiency on large grids (up to 10^4 x 10^4).
 * Uses a sparse Map-based Union-Find to keep space complexity O(L) 
 * instead of O(MN), where L is the number of operations.
 */
class Solution {
    private final int[][] directions = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};

    public List<Integer> numIslands2(int m, int n, int[][] positions) {
        List<Integer> result = new ArrayList<>();
        if (m <= 0 || n <= 0 || positions == null) return result;

        UnionFind uf = new UnionFind();
        for (int[] pos : positions) {
            int r = pos[0];
            int c = pos[1];
            long id = (long) r * n + c;

            // Handle duplicate positions
            if (uf.exists(id)) {
                result.add(uf.count());
                continue;
            }

            uf.add(id);
            for (int[] dir : directions) {
                int nr = r + dir[0];
                int nc = c + dir[1];
                if (nr >= 0 && nr < m && nc >= 0 && nc < n) {
                    long nid = (long) nr * n + nc;
                    if (uf.exists(nid)) {
                        uf.union(id, nid);
                    }
                }
            }
            result.add(uf.count());
        }
        return result;
    }

    /**
     * Sparse Disjoint Set Union (DSU) implementation.
     * Uses HashMap to only store land cells, supporting grids larger than memory.
     */
    private static class UnionFind {
        private final Map<Long, Long> parent = new HashMap<>();
        private final Map<Long, Integer> rank = new HashMap<>();
        private int count = 0;

        public boolean exists(long id) {
            return parent.containsKey(id);
        }

        public void add(long id) {
            parent.put(id, id);
            rank.put(id, 0);
            count++;
        }

        public long find(long id) {
            long curr = id;
            // First pass: find the root
            while (true) {
                long p = parent.get(curr);
                if (p == curr) break;
                curr = p;
            }
            long root = curr;

            // Second pass: path compression
            curr = id;
            while (true) {
                long p = parent.get(curr);
                if (p == root) break;
                parent.put(curr, root);
                curr = p;
            }
            return root;
        }

        public void union(long id1, long id2) {
            long root1 = find(id1);
            long root2 = find(id2);
            if (root1 != root2) {
                int r1 = rank.get(root1);
                int r2 = rank.get(root2);
                
                // Union by rank
                if (r1 < r2) {
                    parent.put(root1, root2);
                } else if (r1 > r2) {
                    parent.put(root2, root1);
                } else {
                    parent.put(root1, root2);
                    rank.put(root2, r1 + 1);
                }
                count--;
            }
        }

        public int count() {
            return count;
        }
    }
}
