# Binary Numbers — Representation, Generation & Division

## What is Binary (Base-2)?

Binary uses only two digits — **0** and **1**. Each position represents a power of 2, read right to left.

```
Position:   7     6     5     4     3     2     1     0
Value:    128    64    32    16     8     4     2     1
```

A `1` bit means "include this power of 2". A `0` bit means "skip it".

---

## Binary Values 0–10

| Decimal | Binary | 8 | 4 | 2 | 1 | Derivation |
|---------|--------|---|---|---|---|------------|
| 0  | `0000` | 0 | 0 | 0 | 0 | nothing |
| 1  | `0001` | 0 | 0 | 0 | 1 | 1 |
| 2  | `0010` | 0 | 0 | 1 | 0 | 2 |
| 3  | `0011` | 0 | 0 | 1 | 1 | 2+1 |
| 4  | `0100` | 0 | 1 | 0 | 0 | 4 |
| 5  | `0101` | 0 | 1 | 0 | 1 | 4+1 |
| 6  | `0110` | 0 | 1 | 1 | 0 | 4+2 |
| 7  | `0111` | 0 | 1 | 1 | 1 | 4+2+1 |
| 8  | `1000` | 1 | 0 | 0 | 0 | 8 |
| 9  | `1001` | 1 | 0 | 0 | 1 | 8+1 |
| 10 | `1010` | 1 | 0 | 1 | 0 | 8+2 |

**Patterns to notice:**
- Even numbers always end in `0`, odd numbers always end in `1`
- Every exact power of 2 (1, 2, 4, 8...) has exactly one `1` bit; all others are `0`
- `0` is simply "no bits set"

---

## Deriving Binary Numbers — Repeated Division by 2

**Method:** Divide by 2 repeatedly, collect the remainders, read them **bottom to top** (reverse order).

### Example: 39 → Binary

```
 n  | n // 2 | n % 2
-----|--------|------
 39 |   19   |   1   ← LSB (least significant bit, rightmost)
 19 |    9   |   1
  9 |    4   |   1
  4 |    2   |   0
  2 |    1   |   0
  1 |    0   |   1   ← MSB (most significant bit, leftmost)
```

Remainders collected top to bottom: `1 0 0 1 1 1` → **39 = `100111`**

### Verify: Expanding bits back to decimal

```
Position:  5    4    3    2    1    0
Bit:       1    0    0    1    1    1
Value:    32 +  0 +  0 +  4 +  2 +  1  =  39  ✓
```

---

## Python Code

### Manual conversion (shows the steps)

```python
def to_binary(n):
    if n == 0:
        return "0"

    remainders = []
    while n > 0:
        remainders.append(n % 2)   # remainder is always 0 or 1
        n = n // 2                 # integer divide

    # remainders collected LSB first — reverse for MSB first
    return ''.join(str(b) for b in reversed(remainders))
```

### Verbose version (prints each step)

```python
def to_binary_verbose(n):
    print(f"Converting {n} to binary:\n")
    print(f"{'n':>6} | {'n // 2':>6} | {'n % 2':>6}")
    print("-" * 26)

    remainders = []
    while n > 0:
        quotient  = n // 2
        remainder = n % 2
        print(f"{n:>6} | {quotient:>6} | {remainder:>6}")
        remainders.append(remainder)
        n = quotient

    result = ''.join(str(b) for b in reversed(remainders))
    print(f"\n Result = {result}")

to_binary_verbose(39)
# Output:
#     39 |     19 |      1
#     19 |      9 |      1
#      9 |      4 |      1
#      4 |      2 |      0
#      2 |      1 |      0
#      1 |      0 |      1
#  Result = 100111
```

### Verify: binary string back to decimal

```python
def from_binary(binary_str):
    total = 0
    for i, bit in enumerate(reversed(binary_str)):
        total += int(bit) * (2 ** i)
    return total

from_binary("100111")  # 39
```

### Python built-ins (for reference)

```python
bin(39)           # '0b100111'  — 0b prefix indicates binary
bin(39)[2:]       # '100111'    — strip the prefix
format(39, 'b')   # '100111'
format(39, '08b') # '00100111' — zero-padded to 8 bits
int('100111', 2)  # 39         — binary string back to decimal
```

---

## Binary Division

Binary long division follows the exact same process as decimal long division — the only digits allowed are `0` and `1`.

### Rules for Binary Arithmetic

```
0 + 0 = 0
0 + 1 = 1
1 + 1 = 10  (write 0, carry 1)

1 - 0 = 1
1 - 1 = 0
0 - 1 = 1   (borrow: 10 - 1 = 1)
```

### Example 1: `110 ÷ 10` (6 ÷ 2 = 3)

```
    11
   ----
10 | 110
     10
    ----
      10
      10
    ----
       0

Quotient = 11 (3)   Remainder = 0
```

### Example 2: `1100 ÷ 11` (12 ÷ 3 = 4)

```
    100
    ----
11 | 1100
     11
    ----
      00
      00
    ----
       0

Quotient = 100 (4)   Remainder = 0
```

### Example 3: `1011 ÷ 11` (11 ÷ 3 = 3 remainder 2)

```
     11
    ----
11 | 1011
     11
    ----
      11
      11
    ----
       0... wait, 10 - 11 can't go → bring down

Quotient = 11 (3)   Remainder = 10 (2)
```

Step-by-step check: `3 × 3 + 2 = 11` ✓

---

## Quick Reference: 0–15 in Binary

| Dec | Bin  | Dec | Bin  |
|-----|------|-----|------|
| 0   | 0000 | 8   | 1000 |
| 1   | 0001 | 9   | 1001 |
| 2   | 0010 | 10  | 1010 |
| 3   | 0011 | 11  | 1011 |
| 4   | 0100 | 12  | 1100 |
| 5   | 0101 | 13  | 1101 |
| 6   | 0110 | 14  | 1110 |
| 7   | 0111 | 15  | 1111 |

---

## Common Mistakes

| Mistake | Correct approach |
|---------|-----------------|
| Reading remainders top-to-bottom | Read **bottom-to-top** (last remainder = MSB) |
| Thinking `1 + 1 = 2` in binary | `1 + 1 = 10` — write 0, carry 1 |
| Forgetting `0` is just `0000` | Zero has no set bits — all positions are 0 |
