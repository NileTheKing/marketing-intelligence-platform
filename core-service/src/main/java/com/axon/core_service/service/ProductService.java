package com.axon.core_service.service;

import com.axon.core_service.domain.product.Product;
import com.axon.core_service.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;

    @Transactional
    public void decreaseStockBatch(Map<Long, Integer> stockDecreases) {
        if (stockDecreases.isEmpty()) {
            return;
        }

        List<Product> products = productRepository.findByIdInWithPessimisticLock(new ArrayList<>(stockDecreases.keySet()));

        for (Product product : products) {
            Integer decreaseAmount = stockDecreases.get(product.getId());
            if (decreaseAmount != null) {
                product.decreaseStock(decreaseAmount);
            }
        }
    }

    @Transactional
    public void decreaseStock(Long productId, Integer quantity) {
        Product product = productRepository.findByIdWithPessimisticLock(productId)
                .orElseThrow(() -> new IllegalArgumentException("Product not found: " + productId));

        product.decreaseStock(quantity);
        log.info("Stock decreased: productId={}, amount={}", productId, quantity);
    }

    @Transactional
    public void syncCampaignStock(Long productId, Long delta) {
        if (delta <= 0) return;

        Product product = productRepository.findByIdWithPessimisticLock(productId)
                .orElseThrow(() -> new IllegalArgumentException("Product not found: " + productId));

        long actualToDecrease = delta;
        if (product.getStock() < delta) {
            log.warn("Stock insufficient for sync! productId={}, stock={}, required={}",
                    productId, product.getStock(), delta);
            actualToDecrease = product.getStock();
        }

        product.decreaseStock(actualToDecrease);
        log.info("Stock synced: productId={}, delta={}", productId, actualToDecrease);
    }
}
