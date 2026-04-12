class Solution {

    private int binLength(int n) {
        int ans = 0;
        while (n != 0) {
            ans += 1;
            n = n / 2;
        }
        return ans-1;
    }

    private int oneLength(int n) {
        int ans = 0;
        while (n != 0) {
            if (n % 2 == 1) ans += 1;
            n = n / 2;
        }
        return ans;
    }


    // Approach 1: Recursive (current)
    // At each step, round up to next power of 2 OR round down to nearest power of 2,
    // recurse on the remainder, take the minimum.
    //
    // How the time complexity is derived:
    //   - Each call makes 2 recursive calls  → branching factor = 2
    //   - Each call reduces n toward a power of 2, halving the bit length each time
    //     → recursion depth = number of bits in n = log₂(n)
    //   - Total nodes in the recursion tree  = 2^depth = 2^(log₂ n)
    //   - 2^(log₂ n) simplifies to n        (by definition: 2^(log₂ n) = n)
    //
    //   Recursion tree for a 3-bit number (depth = log₂n = 3):
    //
    //              f(n)                  ← level 0: 2^0 = 1 call
    //             /    \
    //         f(a)      f(b)             ← level 1: 2^1 = 2 calls
    //        /    \    /    \
    //     f(c) f(d) f(e) f(f)           ← level 2: 2^2 = 4 calls
    //     ...                           ← level 3: 2^3 = 8 calls (base cases)
    //
    //   Total calls = 2^(log₂ n) = n
    //
    // Time:  O(n) worst case
    // Space: O(log n) — recursion stack depth
    public int minOperations(int n) {
        if (oneLength(n) == 0) return 0;
        if (oneLength(n) == 1) return 1;

        int l = binLength(n);
        int max = 1 << (l + 1);
        int min  = 1 << l;

        int diff1 = minOperations(max - n);
        int diff2 = minOperations(n - min);

        return Math.min(diff1, diff2) + 1;

    }

    // Approach 2: Greedy bit scan (optimal)
    //
    // Scan bits from LSB to MSB, greedily decide add or subtract:
    //   - ends in 0  (....0) : shift right — no op, just move to next bit
    //   - ends in 01 (....01): isolated 1, best to subtract → clears this bit, 1 op
    //   - ends in 11 (....11): run of 1s, best to add 1 → carry collapses
    //                          the entire run into a single higher bit, 1 op
    //
    // WHY THE GREEDY IS CORRECT:
    //
    // Case 1 — trailing 0 (....0):
    //   No 1-bit here, nothing to do. Shift right and move on.
    //
    // Case 2 — isolated 1 (....01):
    //   Last bit is 1, bit above it is 0.
    //   - Subtract: ....01 - 1 = ....00  → bit cleared in 1 op. Done with this bit. ✓
    //   - Add:      ....01 + 1 = ....10  → bit just moves up one position, still 1 op.
    //   Both cost 1 op here, but subtract fully eliminates the bit.
    //   Add only shifts the problem up — no gain. So subtract is optimal.
    //
    // Case 3 — run of consecutive 1s (....11):
    //   Last two (or more) bits are 1. Say we have k consecutive 1s.
    //   - Subtract approach: clear each 1 one at a time → k ops total.
    //   - Add approach:      carry propagates through all k ones, collapsing
    //                        the entire run into a single 1 at a higher position.
    //                        Example: 0111 + 1 = 1000  (3 ones → 1 one, in 1 op)
    //                        Then 1 more op later to clear that bit → 2 ops total.
    //   For k = 2:  subtract = 2 ops, add = 2 ops  → tie (add is no worse)
    //   For k ≥ 3:  subtract = k ops, add = 2 ops  → add wins
    //   So add is always at least as good, and strictly better for runs of 3+.
    //
    // These cases are exhaustive and independent — each decision only touches
    // the current group of bits and leaves a clean state (trailing 0s) for the
    // next group. There is no future penalty for either choice, so the greedy
    // local optimum is also the global optimum.
    //
    // Example: n = 39 = 100111
    //   100111  ends in 11 → add 1  → 101000  (ops=1)  run of 3 ones collapsed
    //   101000  ends in 0  → shift  → 10100
    //   10100   ends in 0  → shift  → 1010
    //   1010    ends in 0  → shift  → 101
    //   101     ends in 01 → sub 1  → 100     (ops=2)  isolated 1 cleared
    //   100     ends in 0  → shift  → 10
    //   10      ends in 0  → shift  → 1
    //   1       ends in 01 → sub 1  → 0       (ops=3)  isolated 1 cleared
    //   Total: 3 operations
    //
    // Time:  O(log n) — at most one pass through all bits
    // Space: O(1)
    public int minOperationsOptimal(int n) {
        int ops = 0;
        while (n != 0) {
            if ((n & 1) == 0) {
                n >>= 1;                  // trailing 0: shift, no op
            } else if ((n & 3) == 3) {
                n += 1;                   // ends in 11: add to collapse run
                ops++;
            } else {
                n -= 1;                   // ends in 01: subtract isolated bit
                ops++;
            }
        }
        return ops;
    }

    // Comparison:
    // +----------------+-------------------+-------------------+
    // |                | Approach 1        | Approach 2        |
    // +----------------+-------------------+-------------------+
    // | Strategy       | Recursive min     | Greedy bit scan   |
    // | Time           | O(n) worst case   | O(log n)          |
    // | Space          | O(log n) stack    | O(1)              |
    // | Branches/step  | 2 recursive calls | 1 decision        |
    // +----------------+-------------------+-------------------+
}
