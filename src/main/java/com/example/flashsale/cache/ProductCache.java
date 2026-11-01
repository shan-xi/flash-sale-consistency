package com.example.flashsale.cache;

import com.example.flashsale.api.ProductResponse;
import com.example.flashsale.exception.RedisUnavailableException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis is a disposable cache for the whole product read model.
 * PostgreSQL remains the source of truth.
 */
@Component
public class ProductCache {

    private static final String PREFIX = "product:";
    private static final String SUFFIX = ":detail";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public ProductCache(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    public String key(Long productId) {
        return PREFIX + productId + SUFFIX;
    }

    public CachedProduct get(Long productId) {
        try {
            String value = redis.opsForValue().get(key(productId));
            if (value == null) {
                return null;
            }
            return objectMapper.readValue(value, CachedProduct.class);
        } catch (Exception e) {
            throw new RedisUnavailableException(e);
        }
    }

    public void set(ProductResponse product) {
        try {
            String value = objectMapper.writeValueAsString(
                    new CachedProduct(product.id(), product.name(), product.stock())
            );
            redis.opsForValue().set(key(product.id()), value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize product cache", e);
        } catch (Exception e) {
            throw new RedisUnavailableException(e);
        }
    }

    public void deleteBestEffort(Long productId) {
        try {
            deleteOrThrow(productId);
        } catch (RedisUnavailableException ignored) {
            // PostgreSQL has already committed. The outbox retries invalidation.
        }
    }

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

    public record CachedProduct(Long id, String name, int stock) {
    }
}
