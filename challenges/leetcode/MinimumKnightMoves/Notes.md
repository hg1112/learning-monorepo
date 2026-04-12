# Minimum Knight Moves

[LeetCode 1197](https://leetcode.com/problems/minimum-knight-moves/)

---

## Problem

A knight starts at `(0, 0)` on an infinite chessboard. Find the **minimum number
of moves** to reach `(x, y)`. A knight move is any of `(±1, ±2)` or `(±2, ±1)`.

**Constraints:** `−300 ≤ x, y ≤ 300`

---

## The Board Pattern

Draw the minimum moves from the origin for cells in the first octant (x ≥ y ≥ 0).
The pattern that emerges is the key to the O(1) solution:

```
x →
  0  1  2  3  4  5  6  7
0 0  3  2  3  2  3  4  5
1    2  1  2  3  2  3  4
2       4  3  2  3  4  5    ← (2,2)=4 is the only "anomaly" on the diagonal
3          2  3  4  3  4
4             4  3  4  3
5                4  4  5
6                   4  5
7                      6
```

*(rows = y, columns = x, first octant only — use symmetry for the rest)*

Two things stand out:
1. Values form **diagonal stripes** — a cell's cost depends mainly on `delta = x − y`.
2. Along any diagonal stripe, costs repeat with **period 3** (near-diagonal) or **period 4** (near-axis).

These two observations give the O(1) formula.

---

## Symmetry

A knight's move set is fully symmetric across both axes and the diagonal:

```
f(x, y) = f(−x, y) = f(x, −y) = f(y, x)
```

This means every target maps to a canonical cell in the first octant (`x ≥ y ≥ 0`)
by taking absolute values and swapping if needed. The entire board is covered.

---

## Critical Insight: Paths Can Go Negative

Even for targets in the first quadrant, the shortest path may briefly leave it.

**f(1, 1) = 2, not 4:**
```
(0,0) → (2,−1) → (1,1)
         ↑(+2,−1)  ↑(−1,+2)   both valid knight moves
```
The path dips to `y = −1`. A DP or BFS that never leaves the first quadrant
will return 4 (the "wrong" answer via staying positive).

This is why:
- **BFS needs `−1` padding** (allow `nx,ny ≥ −1`)
- **DP needs the base case `x+y == 2 → 2`** (encodes the 2-step zig-zag without
  explicitly tracking the negative coordinate)

---

## Bugs in the Original Code

### Bug 1 — `HashMap<Integer,Integer>` loses cells with the same x

```java
visitedFromSource.put(2, 1);   // records (2,1)
visitedFromSource.put(2, 3);   // OVERWRITES — (2,1) is forgotten
```

Use `Set<String>` or encode both coordinates into one `long`.

### Bug 2 — Target frontier uses taxi-cab moves

```java
int[][] directions = {{1,0},{0,1},{-1,0},{0,-1}};  // WRONG
```

Both frontiers must expand using knight moves.

### Bug 3 — Source and target frontiers wired backwards

`visitedFromSource.put(x, y)` records the TARGET as a source cell.
`distanceFromSource` gets the entry for `"0_0"` that should go to `distanceFromTarget`.
The meeting-point check adds `source + source` distances instead of `source + target`.

### Bug 4 — Returns 0 on failure

`ans` defaults to 0; if the corrupted frontiers never intersect, the method
returns 0 — correct only for `(0, 0)`.

---

## Approach 1 — BFS (good solution)

Fold to `(|x|, |y|)`, run standard BFS, allow coordinates down to `−1`.

```
Time:  O((max(|x|,|y|))²)
Space: O((max(|x|,|y|))²)
```

**Why `−1` and not `0` as the lower bound?**
The optimal path to a first-quadrant target can use one negative step as a
"springboard." Allowing exactly `−1` is sufficient — no optimal path ever needs
to go to `−2` or below for this problem's constraints.

---

## Approach 2 — Bidirectional BFS

Run two BFS frontiers simultaneously — one from `(0,0)`, one from `(|x|,|y|)` —
and stop when they meet. The combined area explored is roughly **half** that of
unidirectional BFS (two circles of radius d/2 vs one of radius d):

```
2 × π(d/2)² = πd²/2   vs   πd²   (unidirectional)
```

Asymptotically same complexity class but faster in practice.

```
Time:  O((max(|x|,|y|))²)
Space: O((max(|x|,|y|))²)  [doubled data structures offset the smaller search area]
```

**Note on Java:** The HashMap overhead in bidirectional BFS makes it slower than
unidirectional BFS with a 2D boolean array in practice, despite the theoretical
advantage.

---

## Approach 3 — DFS with Memoization

### Recurrence

```
f(x, y) = 1 + min( f(|x−1|, |y−2|),  f(|x−2|, |y−1|) )
```

where `x ≥ y ≥ 0` after normalizing (take abs, swap if needed).

### Base cases

```
f(0, 0) = 0
f(x, y) = 2   when x + y == 2
```

The `x+y==2` case covers `(2,0)`, `(0,2)`, and `(1,1)` — all reachable in
exactly 2 moves by going through a negative coordinate.

### Why NOT `f(1,0) = 3` as the only base case

With `f(1,0)=3` as sole base case, `f(1,1)` recurses to `f(1,0)` and returns 4.
But the correct answer is **2**. The optimal path `(0,0)→(2,−1)→(1,1)` goes
through `y=−1`, which the DP "sees" via the abs-and-normalize step:

```
f(1,1): x+y == 2  →  return 2  ✓
```

### Dependency cycle broken by `x+y==2`

Without it:
```
f(1,0) → f(0,2) → f(2,0) → f(1,0)   ← cycle
```

With it:
```
f(0,2): x+y=2 → 2   (base case, no recursion)
f(2,0): x+y=2 → 2   (base case, no recursion)
f(1,0) = min(f(0,2)=2, f(1,1)=2) + 1 = 3  ✓
```

```
Time:  O(|x| × |y|)
Space: O(|x| × |y|)
```

---

## Approach 4 — O(1) Formula

### Setup

After normalizing `x ≥ y ≥ 0`, define:

```
delta = x − y
```

`delta` is the "excess" of x over y — how far the cell is from the main diagonal.

### Two regions

**Region 1 — near diagonal** (`y > delta`, equivalently `x < 2y`):

The knight can make repeating 3-move sequences that advance by `(3, 3)` net,
keeping `x ≈ y`. Each sequence "saves" 2 steps relative to naive movement.

```
answer = delta − 2 × ⌊(delta − y) / 3⌋
```

Note `delta − y < 0` here (since `y > delta`), so the floor term is negative,
making the overall expression > delta. This correctly inflates the answer
for cells far from the nearest diagonal reachable by the knight.

**Region 2 — near x-axis** (`y ≤ delta`, equivalently `x ≥ 2y`):

The knight advances mainly along x in 4-move blocks gaining `(4, 0)` net.

```
answer = delta − 2 × ⌊(delta − y) / 4⌋
```

Here `delta − y ≥ 0`, so regular integer division works (no float needed).

### Special cases

The general formula fails for exactly two cells:

| Cell | Formula gives | Correct | Why it fails |
|------|--------------|---------|--------------|
| `(1, 0)` | 1 | **3** | Too close to origin; the periodic pattern hasn't "started" yet |
| `(2, 2)` | 2 | **4** | Lies at a phase boundary between period-3 blocks |

All other cells follow the formula exactly.

### Implementation note

The `(delta−y)/3` branch requires `float` cast in Java because `delta−y` is
negative in that region, and Java integer division truncates toward zero (not
floor):

```java
-1 / 3 == 0    // Java integer division (truncate)
Math.floor(-1.0 / 3) == -1.0   // correct floor
```

```
Time:  O(1)
Space: O(1)
```

---

## Worked Examples

### f(2, 1) = 1

Direct knight move. Formula: `delta=1, y=1, y≤delta`. `1−2×⌊0/4⌋ = 1`. ✓

### f(1, 1) = 2

```
(0,0) → (2,−1) → (1,1)     2 moves via negative coordinate
```
Formula: `delta=0, y=1, y>delta`. `0−2×⌊−1/3⌋ = 0−2×(−1) = 2`. ✓

### f(1, 0) = 3

Not reachable in 1 or 2 moves (check exhaustively — none of the 8 one-step
cells has a knight neighbor at `(1,0)`). Special case in formula. ✓

Path: `(0,0) → (1,2) → (3,1) → (1,0)` — goes via `(3,1)`, then `(−2,−1)` step.

### f(2, 2) = 4

Not reachable in 1, 2, or 3 moves. Special case in formula. ✓

Path: `(0,0) → (1,2) → (2,4) → (4,3) → (2,2)`

### f(3, 0) = 3

Formula: `delta=3, y=0, y≤delta`. `3−2×⌊3/4⌋ = 3−0 = 3`. ✓

Path: `(0,0) → (1,2) → (3,1) → ... `? Actually: `(0,0)→(2,1)→(4,2)→(3,0)`? 
`(4,2)+(−1,−2)=(3,0)` ✓. 3 moves.

### f(5, 5) = 4

Formula: `delta=0, y=5, y>delta`. `0−2×⌊−5/3⌋ = 0−2×(−2) = 4`. ✓

Path: `(0,0) → (2,1) → (4,2) → (3,4) → (5,5)`. ✓

---

## Edge Cases

| Input | Answer | Note |
|-------|--------|------|
| `(0, 0)` | 0 | Already there |
| `(1, 0)` / `(0, 1)` | 3 | Formula special case; not reachable in < 3 moves |
| `(2, 2)` | 4 | Formula special case; naive DP without `x+y==2` base gives wrong answer |
| `(1, 1)` | 2 | Counter-intuitive; optimal path goes through `(2,−1)` |
| Negative inputs | Same as positive | 8-way symmetry: `f(x,y) = f(|x|,|y|)` |

---

## Complexity Comparison

| Solution | Correct? | Time | Space |
|----------|----------|------|-------|
| Bad (bidirectional BFS) | No (4 bugs) | — | — |
| BFS + first quadrant | Yes | O(N²), N=max(\|x\|,\|y\|) | O(N²) |
| Bidirectional BFS | Yes (if fixed) | O(N²) | O(N²) |
| DFS + memoization | Yes | O(\|x\|×\|y\|) | O(\|x\|×\|y\|) |
| O(1) formula | Yes | **O(1)** | **O(1)** |
