package com.example.flashsale.service;

import com.example.flashsale.cache.ProductCache;
import com.example.flashsale.domain.Product;
import com.example.flashsale.repository.ProductRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class CacheRebuildService {

    private final ProductRepository productRepository;
    private final ProductCache productCache;
    private final ProductService productService;

    public CacheRebuildService(
            ProductRepository productRepository,
            ProductCache productCache,
            ProductService productService
    ) {
        this.productRepository = productRepository;
        this.productCache = productCache;
        this.productService = productService;
    }

    /**
     * Safety-net rebuild. Redis is disposable, so an empty cache is fine.
     * Each product is rebuilt through the row-lock-protected read path.
     */
    @Scheduled(fixedDelayString = "${app.cache.rebuild-fixed-delay-ms}")
    public void rebuild() {
        if (!productCache.ping()) {
            return;
        }

        for (Product product : productRepository.findAll()) {
            try {
                productService.rebuild(product.getId());
            } catch (Exception ignored) {
                // Redis or DB can be temporarily unavailable. Next run retries.
            }
        }
    }
}
