package com.example.flashsale.cache;

import com.example.flashsale.exception.RedisUnavailableException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class StockCache {

    private static final String PREFIX = "product:";
    private static final String SUFFIX = ":stock";

    private final StringRedisTemplate redis;

    public StockCache(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public String key(Long productId) {
        return PREFIX + productId + SUFFIX;
    }

    /**
     * Cache reads are optional. A Redis failure is converted to a domain
     * exception so callers can fall back to PostgreSQL.
     */
    public Integer get(Long productId) {
        try {
            String value = redis.opsForValue().get(key(productId));
            return value == null ? null : Integer.valueOf(value);
        } catch (Exception e) {
            throw new RedisUnavailableException(e);
        }
    }

    /**
     * Cache writes are optional. Never use this operation as part of the
     * correctness path for a purchase.
     */
    public void set(Long productId, int stock) {
        try {
            redis.opsForValue().set(key(productId), String.valueOf(stock));
        } catch (Exception e) {
            throw new RedisUnavailableException(e);
        }
    }

    /**
     * Best-effort invalidation for the synchronous purchase path.
     */
    public void deleteBestEffort(Long productId) {
        try {
            deleteOrThrow(productId);
        } catch (RedisUnavailableException ignored) {
            // PostgreSQL has already committed. Outbox/rebuild will repair Redis.
        }
    }

    /**
     * Strict invalidation used by the outbox worker. The worker must NOT mark
     * an event processed when Redis did not actually accept the invalidation.
     */
    public void deleteOrThrow(Long productId) {
        try {
            redis.delete(key(productId));
        } catch (Exception e) {
            throw new RedisUnavailableException(e);
        }
    }

    public boolean ping() {
        try {
            String response = redis.getConnectionFactory()
                    .getConnection()
                    .ping();
            return "PONG".equalsIgnoreCase(response);
        } catch (Exception e) {
            return false;
        }
    }
}
