package com.axon.core_service.service;

import com.axon.core_service.AbstractIntegrationTest;
import com.axon.core_service.domain.purchase.Purchase;
import com.axon.core_service.domain.purchase.PurchaseType;
import com.axon.core_service.repository.PurchaseRepository;
import com.axon.core_service.service.batch.CohortLtvBatchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Cohort LTV SQL 집계 로직 직접 검증")
public class CohortLtvSqlOffloadingTest extends AbstractIntegrationTest {

    @Autowired
    private CohortLtvBatchService cohortLtvBatchService;

    @Autowired
    private PurchaseRepository purchaseRepository;

    @BeforeEach
    void setUp() {
        purchaseRepository.deleteAll();
    }

    @Test
    @DisplayName("queryRepeatStats SQL 쿼리가 재구매율과 평균 주문금액을 정확히 계산해야 함")
    void queryRepeatStatsTest() {
        // given: 유저 u1(1건), u2(2건)
        long u1 = 9991L;
        long u2 = 9992L;
        Instant now = Instant.parse("2026-05-01T10:00:00Z");
        LocalDateTime until = LocalDateTime.of(2026, 5, 10, 0, 0);
        
        // u1: 1000원 1건
        purchaseRepository.save(new Purchase(u1, 1L, 1L, PurchaseType.CAMPAIGNACTIVITY, BigDecimal.valueOf(1000), 1, now));
        
        // u2: 1000원(캠페인) + 2000원(일반) = 2건
        purchaseRepository.save(new Purchase(u2, 1L, 1L, PurchaseType.CAMPAIGNACTIVITY, BigDecimal.valueOf(1000), 1, now));
        purchaseRepository.save(new Purchase(u2, 2L, null, PurchaseType.SHOP, BigDecimal.valueOf(2000), 1, now.plus(Duration.ofDays(1))));
        
        purchaseRepository.flush();

        // when: Reflection으로 내부 SQL 집계 메서드 직접 호출 (코호트 유입 쿼리 변수 제거)
        Object repeatResult = ReflectionTestUtils.invokeMethod(cohortLtvBatchService, "queryRepeatStats", List.of(u1, u2), until);
        
        // then: 결과 검증 (RepeatAggResult 레코드 타입 가정)
        // 재구매율: (1 / 2) * 100 = 50.0
        // 평균 빈도: 3건 / 2명 = 1.5
        // AOV: 4000원 / 3건 = 1333.33
        BigDecimal repeatRate = (BigDecimal) ReflectionTestUtils.getField(repeatResult, "repeatRate");
        BigDecimal avgFreq = (BigDecimal) ReflectionTestUtils.getField(repeatResult, "avgFrequency");
        BigDecimal avgOrderValue = (BigDecimal) ReflectionTestUtils.getField(repeatResult, "avgOrderValue");

        System.out.println("DEBUG >>> Result: RepeatRate=" + repeatRate + ", Freq=" + avgFreq + ", AOV=" + avgOrderValue);

        assertThat(repeatRate).isEqualByComparingTo(new BigDecimal("50.00"));
        assertThat(avgFreq).isEqualByComparingTo(new BigDecimal("1.50"));
        assertThat(avgOrderValue.setScale(2, RoundingMode.HALF_UP)).isEqualByComparingTo(new BigDecimal("1333.33"));
    }
}
