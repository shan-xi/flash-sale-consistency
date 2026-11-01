package com.example.flashsale.api;

public record ProductResponse(
        Long id,
        String name,
        int stock,
        String source
) {
}
