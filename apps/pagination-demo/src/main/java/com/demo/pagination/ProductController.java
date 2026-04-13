package com.demo.pagination;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

// Two pagination styles exposed at different endpoints:
//
//   GET /api/products          → offset pagination (page number + size)
//   GET /api/products/cursor   → cursor/keyset pagination (opaque cursor token)
//
// Both support optional ?category=<value> filtering.

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService service;

    public ProductController(ProductService service) {
        this.service = service;
    }

    // -------------------------------------------------------------------------
    // Offset pagination
    // GET /api/products?category=Electronics&page=0&size=10&sortBy=price&sortDir=asc
    // -------------------------------------------------------------------------

    @GetMapping
    public ResponseEntity<PageResponse<Product>> list(
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir) {

        size = Math.min(size, 100); // guard against huge pages
        return ResponseEntity.ok(service.getPage(category, page, size, sortBy, sortDir));
    }

    // -------------------------------------------------------------------------
    // Cursor pagination
    // GET /api/products/cursor?limit=10
    // GET /api/products/cursor?after=<nextCursor>&limit=10
    // GET /api/products/cursor?category=Electronics&after=<nextCursor>&limit=10
    // -------------------------------------------------------------------------

    @GetMapping("/cursor")
    public ResponseEntity<CursorPageResponse<Product>> listCursor(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String after,
            @RequestParam(defaultValue = "10") int limit) {

        limit = Math.min(limit, 100);
        return ResponseEntity.ok(service.getCursorPage(category, after, limit));
    }

    // -------------------------------------------------------------------------
    // Create a single product
    // POST /api/products   body: {"name":"Widget","category":"Electronics","price":9.99}
    // -------------------------------------------------------------------------

    @PostMapping
    public ResponseEntity<Product> create(@RequestBody Product product) {
        return ResponseEntity.status(201).body(service.create(product));
    }
}
