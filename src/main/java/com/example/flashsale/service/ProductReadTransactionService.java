package com.example.flashsale.service;

import com.example.flashsale.api.ProductResponse;
import com.example.flashsale.cache.ProductCache;
import com.example.flashsale.domain.Product;
import com.example.flashsale.repository.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handles the cache-miss path.
 * <p>
 * The product row is locked while the DB snapshot is copied into Redis.
 * This prevents a purchase from committing between the DB read and the
 * cache write and then having the cache resurrect an older stock value.
 */
@Service
public class ProductReadTransactionService {

    private final ProductRepository productRepository;
    private final ProductCache productCache;

    public ProductReadTransactionService(
            ProductRepository productRepository,
            ProductCache productCache
    ) {
        this.productRepository = productRepository;
        this.productCache = productCache;
    }

    /**
     * Cache-miss path. We intentionally use a normal (read-write) transaction
     * because findByIdForUpdate() acquires a PostgreSQL row lock.
     * <p>
     * The lock is held while the DB snapshot is copied to Redis, preventing
     * this race:
     * <p>
     * read stock=100 -> purchase commits stock=99 -> cache writes 100
     * <p>
     * A Redis failure does not roll back or fail the DB read; the caller can
     * still return the PostgreSQL result.
     */
    @Transactional
    public ProductResponse loadAndWarm(Long productId) {
        Product product = productRepository.findByIdForUpdate(productId)
                .orElseThrow(() -> new IllegalArgumentException("Product not found"));

        ProductResponse response = new ProductResponse(
                product.getId(),
                product.getName(),
                product.getStock(),
                "DATABASE"
        );

        try {
            productCache.set(response);
        } catch (RuntimeException ignored) {
            // Redis is optional. The DB result is still valid.
        }

        return response;
    }

    /**
     * Redis-outage path. Do not take a PostgreSQL row lock for a simple read
     * when Redis is unavailable; otherwise a Redis outage could turn every
     * GET into a contended SELECT FOR UPDATE.
     */
    @Transactional(readOnly = true)
    public ProductResponse loadFromDatabase(Long productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new IllegalArgumentException("Product not found"));

        return new ProductResponse(
                product.getId(),
                product.getName(),
                product.getStock(),
                "DATABASE"
        );
    }
}
