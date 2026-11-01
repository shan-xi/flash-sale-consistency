package com.example.flashsale.service;

import com.example.flashsale.api.ProductResponse;
import com.example.flashsale.cache.ProductCache;
import com.example.flashsale.exception.RedisUnavailableException;
import org.springframework.stereotype.Service;

@Service
public class ProductService {

    private final ProductCache productCache;
    private final ProductReadTransactionService readTransactionService;

    public ProductService(
            ProductCache productCache,
            ProductReadTransactionService readTransactionService
    ) {
        this.productCache = productCache;
        this.readTransactionService = readTransactionService;
    }

    /**
     * Cache-aside read:
     * <p>
     * 1. Redis hit -> return immediately. No PostgreSQL query.
     * 2. Redis miss -> PostgreSQL is the source of truth and the result is
     * copied into Redis.
     * 3. Redis down -> PostgreSQL still serves the request.
     */
    public ProductResponse get(Long productId) {
        try {
            ProductCache.CachedProduct cached = productCache.get(productId);
            if (cached != null) {
                return new ProductResponse(
                        cached.id(),
                        cached.name(),
                        cached.stock(),
                        "REDIS"
                );
            }
        } catch (RedisUnavailableException redisUnavailable) {
            // Redis is down: use a normal DB read. Do not acquire a row lock
            // for every GET while Redis is unavailable.
            return readTransactionService.loadFromDatabase(productId);
        }

        // Redis was reachable but the key was absent. Use the row-lock-protected
        // cache-miss path so a concurrent purchase cannot resurrect stale stock.
        return readTransactionService.loadAndWarm(productId);
    }

    /**
     * Manual/cache recovery path. The read transaction service protects the
     * DB-to-cache copy with the product row lock.
     */
    public void rebuild(Long productId) {
        readTransactionService.loadAndWarm(productId);
    }
}
