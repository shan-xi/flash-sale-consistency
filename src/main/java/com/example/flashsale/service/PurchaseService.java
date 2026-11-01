package com.example.flashsale.service;

import com.example.flashsale.api.PurchaseRequest;
import com.example.flashsale.api.PurchaseResponse;
import com.example.flashsale.cache.ProductCache;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
public class PurchaseService {

    private static final Logger log = LoggerFactory.getLogger(PurchaseService.class);

    private final PurchaseTransactionService transactionService;
    private final ProductCache productCache;
    private final RedissonClient redissonClient;
    private final long waitTimeSeconds;
    private final long leaseTimeSeconds;

    public PurchaseService(
            PurchaseTransactionService transactionService,
            ProductCache productCache,
            RedissonClient redissonClient,
            @Value("${app.lock.wait-time-seconds}") long waitTimeSeconds,
            @Value("${app.lock.lease-time-seconds}") long leaseTimeSeconds
    ) {
        this.transactionService = transactionService;
        this.productCache = productCache;
        this.redissonClient = redissonClient;
        this.waitTimeSeconds = waitTimeSeconds;
        this.leaseTimeSeconds = leaseTimeSeconds;
    }

    public PurchaseResponse purchase(Long productId, PurchaseRequest request) {
        RLock lock = redissonClient.getLock("flash-sale:product:" + productId + ":lock");
        boolean lockAcquired = false;
        boolean redisHealthy = true;

        try {
            try {
                lockAcquired = lock.tryLock(
                        waitTimeSeconds,
                        leaseTimeSeconds,
                        TimeUnit.SECONDS
                );
            } catch (RuntimeException redisFailure) {
                // Redisson can throw RedisConnection/RedisTimeout/etc. when Redis is down.
                redisHealthy = false;
                log.warn(
                        "Redis lock unavailable for product {}. Falling back to PostgreSQL row lock.",
                        productId
                );
            }

            if (!lockAcquired && redisHealthy) {
                // false means the lock is currently owned by another caller.
                // Verify Redis is really reachable before treating this as contention.
                redisHealthy = productCache.ping();
                if (redisHealthy) {
                    throw new IllegalStateException("Could not acquire distributed lock");
                }

                log.warn(
                        "Redis became unavailable while acquiring lock for product {}. " +
                                "Falling back to PostgreSQL row lock.",
                        productId
                );
            }

            // PostgreSQL SELECT FOR UPDATE is always executed by the transaction service.
            PurchaseResponse response = transactionService.createOrder(productId, request);

            // Never let Redis failure turn a committed DB transaction into an HTTP failure.
            productCache.deleteBestEffort(productId);
            return response;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for distributed lock", e);
        } finally {
            if (lockAcquired) {
                try {
                    if (lock.isHeldByCurrentThread()) {
                        lock.unlock();
                    }
                } catch (RuntimeException redisFailure) {
                    // The DB transaction has already decided correctness.
                    log.warn(
                            "Could not release Redis lock for product {} because Redis is unavailable.",
                            productId
                    );
                }
            }
        }
    }
}
