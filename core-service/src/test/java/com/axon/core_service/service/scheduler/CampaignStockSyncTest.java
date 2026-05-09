package com.axon.core_service.service.scheduler;

import com.axon.core_service.AbstractIntegrationTest;
import com.axon.core_service.domain.campaign.Campaign;
import com.axon.core_service.domain.campaignactivity.CampaignActivity;
import com.axon.core_service.domain.dto.campaignactivity.CampaignActivityStatus;
import com.axon.core_service.domain.product.Product;
import com.axon.core_service.domain.purchase.Purchase;
import com.axon.core_service.domain.purchase.PurchaseType;
import com.axon.core_service.repository.CampaignActivityRepository;
import com.axon.core_service.repository.CampaignRepository;
import com.axon.core_service.repository.ProductRepository;
import com.axon.core_service.repository.PurchaseRepository;
import com.axon.messaging.CampaignActivityType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("재고 동기화(Delta 기반) 로직 검증 테스트")
public class CampaignStockSyncTest extends AbstractIntegrationTest {

    @Autowired
    private CampaignStockSyncScheduler syncScheduler;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private CampaignActivityRepository activityRepository;

    @Autowired
    private CampaignRepository campaignRepository;

    @Autowired
    private PurchaseRepository purchaseRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private Long productId;
    private Long activityId;

    @BeforeEach
    void setUp() {
        transactionTemplate.execute(status -> {
            purchaseRepository.deleteAll();
            activityRepository.deleteAll();
            campaignRepository.deleteAll();
            productRepository.deleteAll();
            return null;
        });

        transactionTemplate.execute(status -> {
            Product product = productRepository.save(new Product("Test Product", 100L, BigDecimal.valueOf(1000), "General"));
            this.productId = product.getId();

            Campaign campaign = campaignRepository.save(Campaign.builder().name("Sync Campaign").build());

            CampaignActivity activity = activityRepository.save(CampaignActivity.builder()
                    .campaign(campaign)
                    .product(product)
                    .name("Sync Activity")
                    .status(CampaignActivityStatus.ACTIVE)
                    .startDate(LocalDateTime.now().minusDays(1))
                    .endDate(LocalDateTime.now().plusDays(1))
                    .activityType(CampaignActivityType.FIRST_COME_FIRST_SERVE)
                    .price(BigDecimal.valueOf(1000))
                    .quantity(1)
                    .build());
            this.activityId = activity.getId();
            return null;
        });
    }

    @Test
    @DisplayName("스케줄러 반복 실행 시에도 차분(Delta)만큼만 재고가 차감되어야 함")
    void deltaBasedSyncTest() {
        // 1. 1차 주문 발생 (10건)
        transactionTemplate.execute(status -> {
            for (long i = 1; i <= 10; i++) {
                purchaseRepository.save(new Purchase(i, productId, activityId, PurchaseType.CAMPAIGNACTIVITY, BigDecimal.valueOf(1000), 1, Instant.now()));
            }
            return null;
        });

        // 2. 1차 동기화 실행
        syncScheduler.syncOngoingCampaignStocks();

        // 3. 1차 결과 확인: 재고 100 -> 90, syncedCount 0 -> 10
        Product productAfter1st = productRepository.findById(productId).orElseThrow();
        CampaignActivity activityAfter1st = activityRepository.findById(activityId).orElseThrow();
        
        assertThat(productAfter1st.getStock()).isEqualTo(90L);
        assertThat(activityAfter1st.getSyncedCount()).isEqualTo(10);

        // 4. 추가 주문 없이 2차 동기화 실행 (중복 차감 방지 테스트)
        syncScheduler.syncOngoingCampaignStocks();

        // 5. 2차 결과 확인: 재고는 여전히 90이어야 함 (0만큼 차감)
        Product productAfter2nd = productRepository.findById(productId).orElseThrow();
        CampaignActivity activityAfter2nd = activityRepository.findById(activityId).orElseThrow();

        assertThat(productAfter2nd.getStock()).as("중복 차감이 발생하지 않아야 함").isEqualTo(90L);
        assertThat(activityAfter2nd.getSyncedCount()).isEqualTo(10);

        // 6. 2차 주문 발생 (5건 추가, 총 15건)
        transactionTemplate.execute(status -> {
            for (long i = 11; i <= 15; i++) {
                purchaseRepository.save(new Purchase(i, productId, activityId, PurchaseType.CAMPAIGNACTIVITY, BigDecimal.valueOf(1000), 1, Instant.now()));
            }
            return null;
        });

        // 7. 3차 동기화 실행
        syncScheduler.syncOngoingCampaignStocks();

        // 8. 3차 결과 확인: 재고 90 -> 85 (새로 발생한 5건만 차감)
        Product productAfter3rd = productRepository.findById(productId).orElseThrow();
        assertThat(productAfter3rd.getStock()).isEqualTo(85L);
    }
}
