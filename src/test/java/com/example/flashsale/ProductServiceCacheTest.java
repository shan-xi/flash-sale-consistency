package com.example.flashsale;

import com.example.flashsale.api.ProductResponse;
import com.example.flashsale.cache.ProductCache;
import com.example.flashsale.service.ProductReadTransactionService;
import com.example.flashsale.service.ProductService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class ProductServiceCacheTest {

    @Test
    void redisHitDoesNotQueryPostgres() {
        ProductCache cache = mock(ProductCache.class);
        ProductReadTransactionService dbReader = mock(ProductReadTransactionService.class);
        when(cache.get(1L)).thenReturn(
                new ProductCache.CachedProduct(1L, "iPhone 17", 97)
        );

        ProductService service = new ProductService(cache, dbReader);

        ProductResponse response = service.get(1L);

        assertEquals(new ProductResponse(1L, "iPhone 17", 97, "REDIS"), response);
        verify(dbReader, never()).loadAndWarm(1L);
    }

    @Test
    void redisMissReadsPostgresAndWarmsCache() {
        ProductCache cache = mock(ProductCache.class);
        ProductReadTransactionService dbReader = mock(ProductReadTransactionService.class);
        when(cache.get(1L)).thenReturn(null);
        ProductResponse expected = new ProductResponse(1L, "iPhone 17", 96, "DATABASE");
        when(dbReader.loadAndWarm(1L)).thenReturn(expected);

        ProductService service = new ProductService(cache, dbReader);

        assertEquals(expected, service.get(1L));
        verify(dbReader).loadAndWarm(1L);
    }

    @Test
    void redisDownFallsBackToPostgres() {
        ProductCache cache = mock(ProductCache.class);
        ProductReadTransactionService dbReader = mock(ProductReadTransactionService.class);
        when(cache.get(1L)).thenThrow(new RuntimeException("Redis down"));
        ProductResponse expected = new ProductResponse(1L, "iPhone 17", 95, "DATABASE");
        when(dbReader.loadFromDatabase(1L)).thenReturn(expected);

        ProductService service = new ProductService(cache, dbReader);

        assertEquals(expected, service.get(1L));
        verify(dbReader).loadFromDatabase(1L);
        verify(dbReader, never()).loadAndWarm(1L);
    }
}
