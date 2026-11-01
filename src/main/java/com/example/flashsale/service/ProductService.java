package com.example.flashsale.service;

import com.example.flashsale.api.ProductResponse;
import com.example.flashsale.cache.StockCache;
import com.example.flashsale.domain.Product;
import com.example.flashsale.repository.ProductRepository;
import org.springframework.stereotype.Service;

@Service
public class ProductService {

    private final ProductRepository productRepository;
    private final StockCache stockCache;

    public ProductService(ProductRepository productRepository, StockCache stockCache) {
        this.productRepository = productRepository;
        this.stockCache = stockCache;
    }

    public ProductResponse get(Long productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new IllegalArgumentException("Product not found"));

        Integer redisStock = null;
        try {
            redisStock = stockCache.get(productId);
        } catch (Exception ignored) {
        }

        return new ProductResponse(
                product.getId(),
                product.getName(),
                product.getStock(),
                redisStock
        );
    }

    public void rebuild(Long productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new IllegalArgumentException("Product not found"));

        stockCache.set(productId, product.getStock());
    }
}
