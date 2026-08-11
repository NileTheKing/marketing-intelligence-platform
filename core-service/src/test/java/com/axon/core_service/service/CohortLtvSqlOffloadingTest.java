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
        // given: 유저 u1(1건), u2(2건), 취소 구매 1건, 종료 경계의 구매 1건
        long u1 = 9991L;
        long u2 = 9992L;
        long cancelledUser = 9993L;
        long boundaryUser = 9994L;
        Instant now = Instant.parse("2026-05-01T10:00:00Z");
        LocalDateTime until = LocalDateTime.of(2026, 5, 10, 0, 0);
        
        // u1: 1000원 1건
        purchaseRepository.save(new Purchase(u1, 1L, 1L, PurchaseType.CAMPAIGNACTIVITY, BigDecimal.valueOf(1000), 1, now));
        
        // u2: 1000원(캠페인) + 2000원(일반) = 2건
        purchaseRepository.save(new Purchase(u2, 1L, 1L, PurchaseType.CAMPAIGNACTIVITY, BigDecimal.valueOf(1000), 1, now));
        purchaseRepository.save(new Purchase(u2, 2L, null, PurchaseType.SHOP, BigDecimal.valueOf(2000), 1, now.plus(Duration.ofDays(1))));

        Purchase cancelled = new Purchase(cancelledUser, 3L, null, PurchaseType.SHOP,
                BigDecimal.valueOf(9000), 1, now);
        cancelled.cancel("test cancellation", now.plusSeconds(1));
        purchaseRepository.save(cancelled);

        Instant boundary = until.atZone(ZoneId.of("Asia/Seoul")).toInstant();
        purchaseRepository.save(new Purchase(boundaryUser, 4L, null, PurchaseType.SHOP,
                BigDecimal.valueOf(8000), 1, boundary));
        
        purchaseRepository.flush();

        // when: Reflection으로 내부 SQL 집계 메서드 직접 호출 (코호트 유입 쿼리 변수 제거)
        Object repeatResult = ReflectionTestUtils.invokeMethod(cohortLtvBatchService, "queryRepeatStats",
                List.of(u1, u2, cancelledUser, boundaryUser), until);
        
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

    @Test
    @DisplayName("월 집계와 누적 LTV는 취소 구매와 종료 경계를 제외한다")
    void aggregateQueriesUseConfirmedHalfOpenWindow() {
        long userId = 9910L;
        LocalDateTime start = LocalDateTime.of(2026, 5, 1, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 6, 1, 0, 0);
        ZoneId seoul = ZoneId.of("Asia/Seoul");

        purchaseRepository.save(new Purchase(userId, 1L, null, PurchaseType.SHOP,
                BigDecimal.valueOf(1000), 2, start.atZone(seoul).toInstant()));

        Purchase cancelled = new Purchase(userId, 2L, null, PurchaseType.SHOP,
                BigDecimal.valueOf(9000), 1, start.plusDays(1).atZone(seoul).toInstant());
        cancelled.cancel("test cancellation", start.plusDays(2).atZone(seoul).toInstant());
        purchaseRepository.save(cancelled);

        purchaseRepository.save(new Purchase(userId, 3L, null, PurchaseType.SHOP,
                BigDecimal.valueOf(8000), 1, end.atZone(seoul).toInstant()));
        purchaseRepository.flush();

        Object monthly = ReflectionTestUtils.invokeMethod(
                cohortLtvBatchService, "queryMonthlyStats", List.of(userId), start, end);
        BigDecimal cumulative = ReflectionTestUtils.invokeMethod(
                cohortLtvBatchService, "queryCumulativeLtv", List.of(userId), end);

        assertThat((BigDecimal) ReflectionTestUtils.getField(monthly, "monthlyRevenue"))
                .isEqualByComparingTo("2000");
        assertThat((Integer) ReflectionTestUtils.getField(monthly, "monthlyOrders")).isEqualTo(1);
        assertThat(cumulative).isEqualByComparingTo("2000");
    }
}
