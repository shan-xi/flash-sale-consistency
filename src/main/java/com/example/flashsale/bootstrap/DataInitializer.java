package com.example.flashsale.bootstrap;

import com.example.flashsale.domain.Product;
import com.example.flashsale.repository.ProductRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class DataInitializer implements CommandLineRunner {

    private final ProductRepository productRepository;

    public DataInitializer(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (productRepository.findById(1L).isEmpty()) {
            productRepository.save(new Product("iPhone 17", 100));
        }

        // Redis is a disposable cache. Do not make application startup depend
        // on Redis being available. The first GET will warm the cache.
    }
}
