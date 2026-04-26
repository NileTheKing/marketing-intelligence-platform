package com.axon.core_service.service;

import com.axon.core_service.domain.user.RfmSegment;
import com.axon.core_service.domain.user.UserSummary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RFM 세그멘테이션 비즈니스 규칙 단위 테스트
 *
 * VIP     : Recency ≤ 30일  AND Frequency ≥ 3  AND Monetary ≥ 100,000
 * LOYAL   : Recency ≤ 60일  AND Frequency ≥ 2
 * AT_RISK : Recency > 60일  AND Frequency ≥ 1
 * DORMANT : 그 외 (구매 없음 / 장기 미구매 / summary null)
 */
class RfmSegmentationServiceTest {

    private final RfmSegmentationService service = new RfmSegmentationService();
    private final LocalDateTime NOW = LocalDateTime.of(2026, 4, 15, 12, 0);

    // ─────────────────────────────────────────────────
    // VIP
    // ─────────────────────────────────────────────────

    @Test
    @DisplayName("Recency 10일, 구매 3회, 금액 10만원 충족 → VIP 판정")
    void calculateSegment_vipAllConditionsMet_returnsVip() {
        // Given
        UserSummary summary = mock(UserSummary.class);
        when(summary.getLastPurchaseAt()).thenReturn(NOW.minusDays(10));

        // When
        RfmSegment result = service.calculateSegment(summary, 3, 100_000L, NOW);

        // Then
        assertThat(result).isEqualTo(RfmSegment.VIP);
    }

    @Test
    @DisplayName("Recency 30일 경계값, 구매 3회, 금액 10만원 → VIP 판정 (경계 포함)")
    void calculateSegment_vipBoundaryRecency_returnsVip() {
        // Given
        UserSummary summary = mock(UserSummary.class);
        when(summary.getLastPurchaseAt()).thenReturn(NOW.minusDays(30));

        // When
        RfmSegment result = service.calculateSegment(summary, 3, 100_000L, NOW);

        // Then
        assertThat(result).isEqualTo(RfmSegment.VIP);
    }

    @Test
    @DisplayName("Recency 10일, 구매 3회이지만 금액 99,999원 → VIP 미달, LOYAL 판정")
    void calculateSegment_vipMonetaryOneBelowThreshold_returnsLoyal() {
        // Given: Recency·Frequency는 VIP 조건 충족, Monetary만 1원 부족
        UserSummary summary = mock(UserSummary.class);
        when(summary.getLastPurchaseAt()).thenReturn(NOW.minusDays(10));

        // When
        RfmSegment result = service.calculateSegment(summary, 3, 99_999L, NOW);

        // Then: VIP 탈락 후 LOYAL 조건으로 낙하
        assertThat(result).isEqualTo(RfmSegment.LOYAL);
    }

    // ─────────────────────────────────────────────────
    // LOYAL
    // ─────────────────────────────────────────────────

    @Test
    @DisplayName("Recency 45일, 구매 2회 → LOYAL 판정")
    void calculateSegment_loyalConditionsMet_returnsLoyal() {
        // Given
        UserSummary summary = mock(UserSummary.class);
        when(summary.getLastPurchaseAt()).thenReturn(NOW.minusDays(45));

        // When
        RfmSegment result = service.calculateSegment(summary, 2, 50_000L, NOW);

        // Then
        assertThat(result).isEqualTo(RfmSegment.LOYAL);
    }

    @Test
    @DisplayName("Recency 60일 경계값, 구매 2회 → LOYAL 판정 (경계 포함)")
    void calculateSegment_loyalBoundaryRecency_returnsLoyal() {
        // Given
        UserSummary summary = mock(UserSummary.class);
        when(summary.getLastPurchaseAt()).thenReturn(NOW.minusDays(60));

        // When
        RfmSegment result = service.calculateSegment(summary, 2, 30_000L, NOW);

        // Then
        assertThat(result).isEqualTo(RfmSegment.LOYAL);
    }

    // ─────────────────────────────────────────────────
    // AT_RISK
    // ─────────────────────────────────────────────────

    @Test
    @DisplayName("Recency 61일 초과, 구매 이력 1회 → AT_RISK 판정")
    void calculateSegment_overSixtyDaysWithPurchaseHistory_returnsAtRisk() {
        // Given
        UserSummary summary = mock(UserSummary.class);
        when(summary.getLastPurchaseAt()).thenReturn(NOW.minusDays(90));

        // When
        RfmSegment result = service.calculateSegment(summary, 1, 20_000L, NOW);

        // Then
        assertThat(result).isEqualTo(RfmSegment.AT_RISK);
    }

    // ─────────────────────────────────────────────────
    // DORMANT — edge case
    // ─────────────────────────────────────────────────

    @Test
    @DisplayName("summary 객체가 null이면 → DORMANT (신규 가입자, 구매 이력 없음)")
    void calculateSegment_nullSummary_returnsDormant() {
        // When
        RfmSegment result = service.calculateSegment(null, 5, 200_000L, NOW);

        // Then
        assertThat(result).isEqualTo(RfmSegment.DORMANT);
    }

    @Test
    @DisplayName("lastPurchaseAt이 null이면 → DORMANT (구매 기록 없음)")
    void calculateSegment_nullLastPurchaseAt_returnsDormant() {
        // Given
        UserSummary summary = mock(UserSummary.class);
        when(summary.getLastPurchaseAt()).thenReturn(null);

        // When
        RfmSegment result = service.calculateSegment(summary, 3, 100_000L, NOW);

        // Then
        assertThat(result).isEqualTo(RfmSegment.DORMANT);
    }

    @Test
    @DisplayName("구매 횟수 0회이면 → DORMANT (frequency 가드 조건)")
    void calculateSegment_zeroFrequency_returnsDormant() {
        // Given: lastPurchaseAt은 최신이지만 frequency = 0
        UserSummary summary = mock(UserSummary.class);
        when(summary.getLastPurchaseAt()).thenReturn(NOW.minusDays(5));

        // When
        RfmSegment result = service.calculateSegment(summary, 0, 500_000L, NOW);

        // Then
        assertThat(result).isEqualTo(RfmSegment.DORMANT);
    }
}
