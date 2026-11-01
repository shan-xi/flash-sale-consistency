package com.example.flashsale.service;

import com.example.flashsale.api.PurchaseRequest;
import com.example.flashsale.api.PurchaseResponse;
import com.example.flashsale.domain.Order;
import com.example.flashsale.domain.OutboxEvent;
import com.example.flashsale.domain.Product;
import com.example.flashsale.exception.OutOfStockException;
import com.example.flashsale.repository.OrderRepository;
import com.example.flashsale.repository.OutboxRepository;
import com.example.flashsale.repository.ProductRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
public class PurchaseTransactionService {

    private final ProductRepository productRepository;
    private final OrderRepository orderRepository;
    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public PurchaseTransactionService(
            ProductRepository productRepository,
            OrderRepository orderRepository,
            OutboxRepository outboxRepository,
            ObjectMapper objectMapper
    ) {
        this.productRepository = productRepository;
        this.orderRepository = orderRepository;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * PostgreSQL is the mandatory correctness boundary.
     * <p>
     * We lock the product row FIRST, then check idempotency. This ordering is
     * important: two concurrent requests for the same product cannot both
     * pass the idempotency check and then race on the unique request_id index.
     */
    @Transactional
    public PurchaseResponse createOrder(Long productId, PurchaseRequest request) {
        Product product = productRepository.findByIdForUpdate(productId)
                .orElseThrow(() -> new IllegalArgumentException("Product not found"));

        var existing = orderRepository.findByRequestId(request.requestId());
        if (existing.isPresent()) {
            Order order = existing.get();

            if (!order.getProductId().equals(productId)
                    || !order.getUserId().equals(request.userId())
                    || order.getQuantity() != request.quantity()) {
                throw new IllegalArgumentException(
                        "requestId was already used with different purchase data"
                );
            }

            return new PurchaseResponse(
                    order.getId(),
                    order.getProductId(),
                    order.getUserId(),
                    order.getQuantity(),
                    product.getStock()
            );
        }

        if (product.getStock() < request.quantity()) {
            throw new OutOfStockException();
        }

        product.decreaseStock(request.quantity());

        Order order = new Order(
                request.userId(),
                productId,
                request.quantity(),
                request.requestId()
        );
        orderRepository.save(order);

        String payload;
        try {
            // The outbox is an INVALIDATION event, not a stale stock snapshot.
            // The worker deletes the Redis key; cache rebuild then reads the
            // current PostgreSQL value. This avoids an old event overwriting a
            // newer Redis value when events are processed out of order.
            payload = objectMapper.writeValueAsString(
                    Map.of("productId", productId)
            );
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not create outbox payload", e);
        }

        outboxRepository.save(
                new OutboxEvent(productId, "STOCK_CHANGED", payload)
        );

        return new PurchaseResponse(
                order.getId(),
                productId,
                request.userId(),
                request.quantity(),
                product.getStock()
        );
    }
}
