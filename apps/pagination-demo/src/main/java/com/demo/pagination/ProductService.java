package com.demo.pagination;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Base64;
import java.util.List;

@Service
public class ProductService {

    private final ProductRepository repo;

    public ProductService(ProductRepository repo) {
        this.repo = repo;
    }

    // -------------------------------------------------------------------------
    // Offset pagination
    // -------------------------------------------------------------------------

    public PageResponse<Product> getPage(String category, int page, int size, String sortBy, String sortDir) {
        Sort sort = sortDir.equalsIgnoreCase("asc")
            ? Sort.by(sortBy).ascending()
            : Sort.by(sortBy).descending();

        Pageable pageable = PageRequest.of(page, size, sort);

        Page<Product> result = (category != null && !category.isBlank())
            ? repo.findByCategory(category, pageable)
            : repo.findAll(pageable);

        return new PageResponse<>(
            result.getContent(),
            result.getTotalElements(),
            result.getTotalPages(),
            result.getNumber(),
            result.getSize(),
            result.hasNext(),
            result.hasPrevious()
        );
    }

    // -------------------------------------------------------------------------
    // Cursor (keyset) pagination
    // -------------------------------------------------------------------------

    public CursorPageResponse<Product> getCursorPage(String category, String after, int limit) {
        // Fetch (limit + 1) rows: if we get limit+1, there is a next page.
        Pageable pageable = PageRequest.of(0, limit + 1);

        List<Product> results;
        if (after == null) {
            results = repo.findFirstPage(category, pageable);
        } else {
            long[] decoded = decodeCursor(after);
            long cursorId          = decoded[0];
            Instant cursorCreatedAt = Instant.ofEpochMilli(decoded[1]);
            results = repo.findAfterCursor(category, cursorCreatedAt, cursorId, pageable);
        }

        boolean hasMore = results.size() > limit;
        List<Product> page = hasMore ? results.subList(0, limit) : results;
        String nextCursor = hasMore ? encodeCursor(page.get(page.size() - 1)) : null;

        return new CursorPageResponse<>(page, nextCursor, hasMore);
    }

    public Product create(Product product) {
        return repo.save(product);
    }

    // -------------------------------------------------------------------------
    // Cursor encoding
    // Encodes the last item's (id, createdAt) as opaque Base64 so the client
    // cannot guess or manipulate the cursor.
    // -------------------------------------------------------------------------

    private String encodeCursor(Product last) {
        String raw = last.getId() + "|" + last.getCreatedAt().toEpochMilli();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes());
    }

    private long[] decodeCursor(String cursor) {
        String raw = new String(Base64.getUrlDecoder().decode(cursor));
        String[] parts = raw.split("\\|", 2);
        return new long[]{ Long.parseLong(parts[0]), Long.parseLong(parts[1]) };
    }
}
