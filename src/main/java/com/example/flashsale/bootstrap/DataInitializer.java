package com.example.flashsale.bootstrap;

import com.example.flashsale.cache.StockCache;
import com.example.flashsale.domain.Product;
import com.example.flashsale.repository.ProductRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class DataInitializer implements CommandLineRunner {

    private final ProductRepository productRepository;
    private final StockCache stockCache;

    public DataInitializer(ProductRepository productRepository, StockCache stockCache) {
        this.productRepository = productRepository;
        this.stockCache = stockCache;
    }

    @Override
    @Transactional
    public void run(String... args) {
        Product product = productRepository.findById(1L).orElse(null);

        if (product == null) {
            product = productRepository.save(
                    new Product("iPhone 17", 100)
            );
        }

        try {
            stockCache.set(product.getId(), product.getStock());
        } catch (Exception ignored) {
            // Redis may not be available at startup.
        }
    }
}
