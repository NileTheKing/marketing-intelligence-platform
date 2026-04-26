package com.axon.core_service.performance;

import com.axon.core_service.AbstractIntegrationTest;
import com.axon.core_service.domain.product.Product;
import com.axon.core_service.domain.purchase.PurchaseType;
import com.axon.core_service.repository.ProductRepository;
import com.axon.core_service.service.ProductService;
import com.axon.core_service.service.purchase.PurchaseService;
import com.axon.core_service.domain.dto.purchase.PurchaseInfoDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StopWatch;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

public class StockPerformanceBenchmarkTest extends AbstractIntegrationTest {

    @Autowired
    private ProductService productService;

    @Autowired
    private PurchaseService purchaseService;

    @Autowired
    private ProductRepository productRepository;

    private Long testProductId;
    private final int THREAD_COUNT = 100;
    private final int TOTAL_REQUESTS = 100;

    @BeforeEach
    void setUp() {
        Product product = new Product("Performance Test Product", 10000L, BigDecimal.valueOf(1000), "TEST");
        Product saved = productRepository.save(product);
        testProductId = saved.getId();
    }

    @Test
    @DisplayName("Performance Benchmark: Synchronous Row Lock vs Deferred Log Insert")
    void benchmarkStockUpdatePerformance() throws InterruptedException {
        // --- [Scenario A: Synchronous Row-Locking Update] ---
        StopWatch syncWatch = new StopWatch("Scenario A: Synchronous");
        syncWatch.start();
        
        runConcurrentTask(() -> {
            productService.decreaseStock(testProductId, 1);
        });
        
        syncWatch.stop();
        long syncTime = syncWatch.getTotalTimeMillis();
        System.out.println(">>> Scenario A (Sync/Lock) Total Time: " + syncTime + "ms");

        // --- [Scenario B: Deferred Log Insert (Our Strategy)] ---
        StopWatch asyncWatch = new StopWatch("Scenario B: Deferred");
        asyncWatch.start();
        
        runConcurrentTask(() -> {
            PurchaseInfoDto dto = new PurchaseInfoDto(
                1L,             // campaignId
                11L,            // campaignActivityId
                999L,           // userId
                testProductId,  // productId
                Instant.now(),  // occurredAt
                PurchaseType.CAMPAIGNACTIVITY, // purchaseType
                BigDecimal.valueOf(1000),      // price
                1,                             // quantity
                Instant.now()                  // purchasedAt
            );
            purchaseService.createPurchase(dto);
        });
        
        asyncWatch.stop();
        long asyncTime = asyncWatch.getTotalTimeMillis();
        System.out.println(">>> Scenario B (Deferred/Insert) Total Time: " + asyncTime + "ms");

        // Results Print
        System.out.println("=================================================");
        System.out.println(" Performance Comparison (for " + TOTAL_REQUESTS + " requests)");
        System.out.println(" - Sync Row-Lock: " + syncTime + "ms");
        System.out.println(" - Deferred Log: " + asyncTime + "ms");
        if (asyncTime > 0) {
            System.out.println(" - Speed Improvement: " + (double)syncTime / asyncTime + "x faster");
        }
        System.out.println("=================================================");

        assertThat(asyncTime).isLessThan(syncTime);
    }

    private void runConcurrentTask(Runnable task) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(32);
        CountDownLatch latch = new CountDownLatch(THREAD_COUNT);

        for (int i = 0; i < THREAD_COUNT; i++) {
            executor.submit(() -> {
                try {
                    task.run();
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
        executor.shutdown();
    }
}
