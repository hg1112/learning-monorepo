package com.demo.pagination;

import java.util.List;

// Offset-based pagination response.
//
// Trade-offs:
//   + Random access: jump to any page number directly.
//   + Simple to implement with Spring Data's Pageable.
//   - Skips rows on large offsets: OFFSET 10000 scans & discards 10k rows → O(N).
//   - Unstable under concurrent writes: a new insert at page 0 shifts everything,
//     causing rows to appear on the next page that the client already saw, or
//     rows to be skipped entirely.
//
// Use for: admin lists, low-traffic catalogs, small datasets (< 10k rows).
public record PageResponse<T>(
    List<T> data,
    long totalElements,   // total matching rows (requires COUNT(*) query)
    int totalPages,
    int currentPage,
    int pageSize,
    boolean hasNext,
    boolean hasPrev
) {}
