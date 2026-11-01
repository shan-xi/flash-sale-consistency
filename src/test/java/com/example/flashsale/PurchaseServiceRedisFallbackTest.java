package com.example.flashsale;

import com.example.flashsale.api.PurchaseRequest;
import com.example.flashsale.api.PurchaseResponse;
import com.example.flashsale.cache.ProductCache;
import com.example.flashsale.service.PurchaseService;
import com.example.flashsale.service.PurchaseTransactionService;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class PurchaseServiceRedisFallbackTest {

    @Test
    void redisExceptionFallsBackToPostgresTransaction() throws Exception {
        PurchaseTransactionService transactionService = mock(PurchaseTransactionService.class);
        ProductCache productCache = mock(ProductCache.class);
        RedissonClient redissonClient = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);

        when(redissonClient.getLock("flash-sale:product:1:lock")).thenReturn(lock);
        when(lock.tryLock(anyLong(), anyLong(), any()))
                .thenThrow(mock(RedisException.class));

        PurchaseRequest request = new PurchaseRequest(1001L, 1, "request-redis-down-1");
        PurchaseResponse expected = new PurchaseResponse(123L, 1L, 1001L, 1, 99);
        when(transactionService.createOrder(1L, request)).thenReturn(expected);

        PurchaseService service = new PurchaseService(
                transactionService, productCache, redissonClient, 2, 30
        );

        assertEquals(expected, service.purchase(1L, request));
        verify(transactionService).createOrder(1L, request);
        verify(productCache).deleteBestEffort(1L);
        verify(lock, never()).unlock();
    }

    @Test
    void falseLockPlusRedisDownFallsBackToPostgresTransaction() throws Exception {
        PurchaseTransactionService transactionService = mock(PurchaseTransactionService.class);
        ProductCache productCache = mock(ProductCache.class);
        RedissonClient redissonClient = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);

        when(redissonClient.getLock("flash-sale:product:1:lock")).thenReturn(lock);
        when(lock.tryLock(anyLong(), anyLong(), any())).thenReturn(false);
        when(productCache.ping()).thenReturn(false);

        PurchaseRequest request = new PurchaseRequest(1002L, 1, "request-redis-down-2");
        PurchaseResponse expected = new PurchaseResponse(124L, 1L, 1002L, 1, 98);
        when(transactionService.createOrder(1L, request)).thenReturn(expected);

        PurchaseService service = new PurchaseService(
                transactionService, productCache, redissonClient, 2, 30
        );

        assertEquals(expected, service.purchase(1L, request));
        verify(transactionService).createOrder(1L, request);
    }

    @Test
    void healthyRedisWithLockContentionDoesNotBypassDistributedLock() throws Exception {
        PurchaseTransactionService transactionService = mock(PurchaseTransactionService.class);
        ProductCache productCache = mock(ProductCache.class);
        RedissonClient redissonClient = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);

        when(redissonClient.getLock("flash-sale:product:1:lock")).thenReturn(lock);
        when(lock.tryLock(anyLong(), anyLong(), any())).thenReturn(false);
        when(productCache.ping()).thenReturn(true);

        PurchaseService service = new PurchaseService(
                transactionService, productCache, redissonClient, 2, 30
        );

        assertThrows(
                IllegalStateException.class,
                () -> service.purchase(
                        1L,
                        new PurchaseRequest(1003L, 1, "request-lock-busy")
                )
        );

        verify(transactionService, never()).createOrder(anyLong(), any());
    }
}
