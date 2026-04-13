package com.demo.pagination;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface ProductRepository extends JpaRepository<Product, Long> {

    // --- Offset pagination ---------------------------------------------------

    // Spring Data translates Pageable → LIMIT + OFFSET + optional COUNT(*).
    // Page<T> carries the total count; Slice<T> skips the count query.
    Page<Product> findAll(Pageable pageable);

    Page<Product> findByCategory(String category, Pageable pageable);

    // --- Cursor (keyset) pagination ------------------------------------------
    //
    // Keyset condition: advance past the last seen (createdAt, id) pair.
    // Composite ORDER BY must match the composite index on (created_at, id).
    // Pageable here carries only the LIMIT — sort is in the query itself.

    @Query("""
        SELECT p FROM Product p
        WHERE (:category IS NULL OR p.category = :category)
          AND (p.createdAt < :cursorCreatedAt
               OR (p.createdAt = :cursorCreatedAt AND p.id < :cursorId))
        ORDER BY p.createdAt DESC, p.id DESC
        """)
    List<Product> findAfterCursor(
        @Param("category") String category,
        @Param("cursorCreatedAt") Instant cursorCreatedAt,
        @Param("cursorId") Long cursorId,
        Pageable pageable
    );

    @Query("""
        SELECT p FROM Product p
        WHERE (:category IS NULL OR p.category = :category)
        ORDER BY p.createdAt DESC, p.id DESC
        """)
    List<Product> findFirstPage(
        @Param("category") String category,
        Pageable pageable
    );
}
