package com.example.flashsale.service;

import com.example.flashsale.cache.StockCache;
import com.example.flashsale.domain.Product;
import com.example.flashsale.repository.ProductRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class CacheRebuildService {

    private final ProductRepository productRepository;
    private final StockCache stockCache;

    public CacheRebuildService(
            ProductRepository productRepository,
            StockCache stockCache
    ) {
        this.productRepository = productRepository;
        this.stockCache = stockCache;
    }

    @Scheduled(fixedDelayString = "${app.cache.rebuild-fixed-delay-ms}")
    public void rebuild() {
        for (Product product : productRepository.findAll()) {
            try {
                stockCache.set(product.getId(), product.getStock());
            } catch (Exception e) {
                // Redis may still be unavailable.
                return;
            }
        }
    }
}
