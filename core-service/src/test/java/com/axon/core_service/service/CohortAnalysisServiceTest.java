package com.axon.core_service.service;

import com.axon.core_service.domain.campaignactivity.CampaignActivity;
import com.axon.core_service.domain.dto.dashboard.CohortAnalysisResponse;
import com.axon.core_service.domain.purchase.Purchase;
import com.axon.core_service.domain.purchase.PurchaseType;
import com.axon.core_service.repository.CampaignActivityRepository;
import com.axon.core_service.repository.PurchaseRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CohortAnalysisService TDD 기반 단위 테스트")
public class CohortAnalysisServiceTest {

    @Mock
    private PurchaseRepository purchaseRepository;

    @Mock
    private CampaignActivityRepository campaignActivityRepository;

    @InjectMocks
    private CohortAnalysisService cohortAnalysisService;

    @Test
    @DisplayName("정상 케이스: 코호트 LTV 및 CAC 계산 검증")
    void analyzeCohortByActivity_Success() {
        // given
        Long activityId = 1L;
        LocalDateTime startDate = LocalDateTime.of(2025, 1, 1, 0, 0);
        ZoneId zoneId = ZoneId.of("Asia/Seoul");
        Instant startInstant = startDate.atZone(zoneId).toInstant();

        CampaignActivity activity = CampaignActivity.builder()
                .name("New Year Promotion")
                .startDate(startDate)
                .budget(new BigDecimal("1000.00"))
                .build();
        
        // Mocking user: 2 users
        // User 1 first purchase at Jan 1
        Purchase p1 = Purchase.builder()
                .userId(101L)
                .productId(1L)
                .campaignActivityId(activityId)
                .price(new BigDecimal("100.00"))
                .quantity(1)
                .purchaseType(PurchaseType.CAMPAIGNACTIVITY)
                .purchasedAt(startInstant)
                .build();
        
        // User 2 first purchase at Jan 2
        Purchase p2 = Purchase.builder()
                .userId(102L)
                .productId(2L)
                .campaignActivityId(activityId)
                .price(new BigDecimal("200.00"))
                .quantity(1)
                .purchaseType(PurchaseType.CAMPAIGNACTIVITY)
                .purchasedAt(startInstant.plusSeconds(86400)) // +1 day
                .build();

        // User 1 repeat purchase at Jan 15 (within 30 days)
        Purchase p1Repeat = Purchase.builder()
                .userId(101L)
                .productId(3L)
                .price(new BigDecimal("50.00"))
                .quantity(2)
                .purchaseType(PurchaseType.SHOP)
                .purchasedAt(startInstant.plusSeconds(86400 * 14)) // +14 days
                .build();

        when(campaignActivityRepository.findById(activityId)).thenReturn(Optional.of(activity));
        when(purchaseRepository.findFirstPurchasesByActivityAndPeriod(eq(activityId), any(), any()))
                .thenReturn(List.of(p1, p2));
        when(purchaseRepository.findByUserIdIn(any())).thenReturn(List.of(p1, p2, p1Repeat));

        // when
        CohortAnalysisResponse response = cohortAnalysisService.analyzeCohortByActivity(activityId, null, null);

        // then
        // CAC = Budget(1000) / Users(2) = 500
        assertThat(response.avgCAC()).isEqualByComparingTo("500.00");
        assertThat(response.totalCustomers()).isEqualTo(2L);

        // LTV Calculation:
        // Total revenue = (100*1) + (200*1) + (50*2) = 100 + 200 + 100 = 400
        // Avg LTV = 400 / 2 = 200
        assertThat(response.ltvCurrent()).isEqualByComparingTo("200.00");
        
        // LTV/CAC Ratio = 200 / 500 = 0.4
        assertThat(response.ltvCacRatioCurrent()).isEqualTo(0.4);

        // Repeat Purchase Rate: User 1 repeated, User 2 didn't. 1/2 = 50%
        assertThat(response.repeatPurchaseRate()).isEqualTo(50.0);
    }

    @Test
    @DisplayName("엣지 케이스: 구매 데이터가 없을 때 빈 응답 반환")
    void analyzeCohortByActivity_NoData() {
        // given
        Long activityId = 1L;
        CampaignActivity activity = CampaignActivity.builder()
                .name("Empty Activity")
                .startDate(LocalDateTime.now())
                .build();

        when(campaignActivityRepository.findById(activityId)).thenReturn(Optional.of(activity));
        when(purchaseRepository.findFirstPurchasesByActivityAndPeriod(any(), any(), any()))
                .thenReturn(List.of());

        // when
        CohortAnalysisResponse response = cohortAnalysisService.analyzeCohortByActivity(activityId, null, null);

        // then
        assertThat(response.totalCustomers()).isEqualTo(0L);
        assertThat(response.avgCAC()).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
