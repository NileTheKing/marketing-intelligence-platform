package com.axon.core_service.service;

import com.axon.core_service.AbstractIntegrationTest;
import com.axon.core_service.domain.purchase.Purchase;
import com.axon.core_service.repository.PurchaseRepository;
import com.axon.messaging.CampaignActivityType;
import com.axon.messaging.dto.CampaignActivityKafkaProducerDto;
import com.axon.core_service.domain.campaign.Campaign;
import com.axon.core_service.domain.campaignactivity.CampaignActivity;
import com.axon.core_service.domain.dto.campaignactivity.CampaignActivityStatus;
import com.axon.core_service.domain.product.Product;
import com.axon.core_service.repository.CampaignActivityRepository;
import com.axon.core_service.repository.CampaignRepository;
import com.axon.core_service.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.shaded.org.awaitility.Awaitility;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("End-to-End: Kafka to MySQL 구매 플로우 통합 테스트")
public class PurchaseFlowIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private PurchaseRepository purchaseRepository;

    @Autowired
    private CampaignRepository campaignRepository;

    @Autowired
    private CampaignActivityRepository campaignActivityRepository;

    @Autowired
    private ProductRepository productRepository;

    private Long activityId;
    private Long productId;

    @BeforeEach
    void setUp() {
        purchaseRepository.deleteAll();
        campaignActivityRepository.deleteAll();
        campaignRepository.deleteAll();
        productRepository.deleteAll();

        Campaign campaign = Campaign.builder()
                .name("E2E Test Campaign")
                .build();
        campaign = campaignRepository.save(campaign);

        Product product = new Product("E2E Test Product", 100L, BigDecimal.valueOf(10000), "General");
        product = productRepository.save(product);
        this.productId = product.getId();

        CampaignActivity activity = CampaignActivity.builder()
                .campaign(campaign)
                .product(product)
                .name("E2E FCFS Activity")
                .limitCount(100)
                .status(CampaignActivityStatus.ACTIVE)
                .startDate(LocalDateTime.now())
                .endDate(LocalDateTime.now().plusDays(1))
                .activityType(CampaignActivityType.FIRST_COME_FIRST_SERVE)
                .price(BigDecimal.valueOf(10000))
                .quantity(100)
                .build();
        activity = campaignActivityRepository.save(activity);
        this.activityId = activity.getId();
    }

    @Test
    @DisplayName("Kafka 메시지 발행 시 Core Service가 소모하여 DB에 Purchase를 저장해야 함")
    void kafkaToPurchaseIntegrationTest() {
        // given
        Long userId = 777L;
        CampaignActivityKafkaProducerDto dto = CampaignActivityKafkaProducerDto.builder()
                .userId(userId)
                .productId(productId)
                .campaignActivityId(activityId)
                .campaignActivityType(CampaignActivityType.FIRST_COME_FIRST_SERVE)
                .timestamp(System.currentTimeMillis())
                .build();

        // when: Kafka 전송
        kafkaTemplate.send("axon.campaign-activity.command", dto);

        // then: 비동기 처리를 기다려 DB 적재 확인 (Awaitility 사용)
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    List<Purchase> purchases = purchaseRepository.findByUserIdIn(List.of(userId));
                    assertThat(purchases).isNotEmpty();
                    assertThat(purchases.get(0).getUserId()).isEqualTo(userId);
                    assertThat(purchases.get(0).getProductId()).isEqualTo(productId);
                });
    }
}
