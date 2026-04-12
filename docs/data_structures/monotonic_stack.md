# Monotonic Stack

A specialized stack that maintains elements in a specific order (either non-increasing or non-decreasing).

### Visual Representation (Increasing Stack)
When a new element `X` is smaller than `top`, we pop elements until `X` can be pushed.

```text
Input: [2, 10, 5, 8]

1. Push 2:      [ 2 ]
2. Push 10:     [ 2, 10 ]
3. Push 5:      Pop 10 -> [ 2, 5 ]    <-- 10 was "blocked" by 5
4. Push 8:      [ 2, 5, 8 ]

Final Stack: [ 2, 5, 8 ]
```

### Key Use Cases
- Next Greater Element
- Largest Rectangle in Histogram
- Trapping Rain Water
