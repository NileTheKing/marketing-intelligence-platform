package com.axon.core_service.service;

import com.axon.core_service.AbstractIntegrationTest;
import com.axon.core_service.domain.campaign.Campaign;
import com.axon.core_service.domain.campaignactivity.CampaignActivity;
import com.axon.core_service.domain.campaignactivityentry.CampaignActivityEntry;
import com.axon.core_service.domain.campaignactivityentry.CampaignActivityEntryStatus;
import com.axon.core_service.domain.dto.campaignactivity.CampaignActivityStatus;
import com.axon.core_service.domain.product.Product;
import com.axon.core_service.repository.CampaignActivityEntryRepository;
import com.axon.core_service.repository.CampaignActivityRepository;
import com.axon.core_service.repository.CampaignRepository;
import com.axon.core_service.repository.ProductRepository;
import com.axon.messaging.CampaignActivityType;
import com.axon.messaging.dto.CampaignActivityKafkaProducerDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CampaignActivityEntryService 배치 복구 로직 검증")
public class CampaignActivityEntryRetryTest extends AbstractIntegrationTest {

    @Autowired
    private CampaignActivityEntryService campaignActivityEntryService;

    @Autowired
    private CampaignActivityEntryRepository campaignActivityEntryRepository;

    @Autowired
    private CampaignActivityRepository campaignActivityRepository;

    @Autowired
    private CampaignRepository campaignRepository;

    @Autowired
    private ProductRepository productRepository;

    private CampaignActivity activity;

    @BeforeEach
    void setUp() {
        campaignActivityEntryRepository.deleteAll();
        campaignActivityRepository.deleteAll();
        campaignRepository.deleteAll();
        productRepository.deleteAll();

        Campaign campaign = campaignRepository.save(Campaign.builder().name("Test Campaign").build());
        Product product = productRepository.save(new Product("Test Product", 100L, BigDecimal.valueOf(10000), "General"));

        activity = campaignActivityRepository.save(CampaignActivity.builder()
                .campaign(campaign)
                .product(product)
                .name("FCFS Activity")
                .limitCount(100)
                .status(CampaignActivityStatus.ACTIVE)
                .startDate(LocalDateTime.now().minusDays(1))
                .endDate(LocalDateTime.now().plusDays(1))
                .activityType(CampaignActivityType.FIRST_COME_FIRST_SERVE)
                .price(BigDecimal.valueOf(10000))
                .quantity(1)
                .build());
    }

    @Test
    @DisplayName("배치 저장 중 중복 데이터가 섞여 있어도 나머지 정상 데이터는 저장되어야 함")
    void upsertBatchFallbackTest() {
        Long userIdDuplicate = 1L;
        Long userIdNormal = 2L;

        // 1번 유저 미리 저장
        campaignActivityEntryRepository.save(CampaignActivityEntry.create(activity, userIdDuplicate, activity.getProduct().getId(), java.time.Instant.now()));

        CampaignActivityKafkaProducerDto msg1 = CampaignActivityKafkaProducerDto.builder()
                .userId(userIdDuplicate)
                .campaignActivityId(activity.getId())
                .productId(activity.getProduct().getId())
                .build();

        CampaignActivityKafkaProducerDto msg2 = CampaignActivityKafkaProducerDto.builder()
                .userId(userIdNormal)
                .campaignActivityId(activity.getId())
                .productId(activity.getProduct().getId())
                .build();

        // when: 중복 포함된 배치를 처리 (내부적으로 개별 트랜잭션 리트라이 발생)
        campaignActivityEntryService.upsertBatch(
                Map.of(activity.getId(), activity),
                List.of(msg1, msg2),
                CampaignActivityEntryStatus.APPROVED
        );

        // then: 2번 유저(정상 데이터)가 DB에 저장되어 있어야 함
        assertThat(campaignActivityEntryRepository.findByCampaignActivity_IdAndUserId(activity.getId(), userIdNormal)).isPresent();
        assertThat(campaignActivityEntryRepository.count()).isEqualTo(2);
    }
}
