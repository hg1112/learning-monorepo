package com.demo.pagination;

import java.util.List;

// Cursor-based (keyset) pagination response.
//
// The cursor encodes the last item's (createdAt, id) as Base64.
// Next page query: WHERE (created_at, id) < (cursor.createdAt, cursor.id)
//                  ORDER BY created_at DESC, id DESC LIMIT N
//
// Trade-offs:
//   + O(log N): uses the composite index — no row scanning.
//   + Stable: concurrent inserts/deletes don't shift positions; no duplicate or
//     skipped rows between pages.
//   - No random page access: must walk forward (or backward) from the cursor.
//   - Total count is expensive; usually omitted.
//
// Use for: infinite scroll, feeds, real-time lists, tables with >100k rows.
public record CursorPageResponse<T>(
    List<T> data,
    String nextCursor,   // null when on the last page
    boolean hasMore
) {}
