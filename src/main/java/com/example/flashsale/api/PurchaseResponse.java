package com.example.flashsale.api;

public record PurchaseResponse(
        Long orderId,
        Long productId,
        Long userId,
        int quantity,
        int remainingStock
) {
}
