package com.example.flashsale.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record PurchaseRequest(
        @NotNull Long userId,
        @Min(1) int quantity,
        @NotBlank String requestId
) {
}
