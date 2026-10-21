package com.axon.core_service.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.axon.core_service.domain.user.User;
import com.axon.core_service.domain.purchase.Purchase;
import com.axon.core_service.domain.purchase.PurchaseStatus;
import com.axon.core_service.repository.PurchaseRepository;
import com.axon.core_service.repository.UserRepository;
import com.axon.core_service.repository.UserSummaryRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
class UserSummaryServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PurchaseRepository purchaseRepository;

    @Mock
    private UserSummaryRepository userSummaryRepository;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private UserSummaryService userSummaryService;

    @Test
    @DisplayName("recordPurchase는 사용자에 대한 마지막 구매 시각을 갱신한다")
    void recordPurchaseUpdatesLastPurchase() {
        Instant occurredAt = Instant.now();
        java.time.LocalDateTime candidate = java.time.LocalDateTime.ofInstant(
                occurredAt, java.time.ZoneId.of("Asia/Seoul"));
        when(userSummaryRepository.advanceLastPurchaseAt(1L, candidate)).thenReturn(1);

        userSummaryService.recordPurchase(1L, occurredAt);

        verify(userSummaryRepository).advanceLastPurchaseAt(1L, candidate);
    }

    @Test
    @DisplayName("recordPurchase는 사용자가 존재하지 않을 경우 예외를 던진다")
    void recordPurchaseThrowsIfUserNotFound() {
        Instant occurredAt = Instant.now();
        java.time.LocalDateTime candidate = java.time.LocalDateTime.ofInstant(
                occurredAt, java.time.ZoneId.of("Asia/Seoul"));
        when(userSummaryRepository.advanceLastPurchaseAt(2L, candidate)).thenReturn(0);
        when(userSummaryRepository.existsById(2L)).thenReturn(false);

        assertThatThrownBy(() -> userSummaryService.recordPurchase(2L, occurredAt))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("User summary not found: 2");
    }

    @Test
    @DisplayName("배치 projection은 null 시각을 무시하고 CASE 기반 단일 UPDATE를 사용한다")
    @SuppressWarnings("unchecked")
    void recordLatestPurchaseBatchUsesBulkUpdate() {
        Instant occurredAt = Instant.parse("2026-08-01T00:00:00Z");
        doReturn(List.of(1L)).when(jdbcTemplate).query(
                anyString(), org.mockito.ArgumentMatchers.<RowMapper<Long>>any(), any(Object[].class));
        Map<Long, Instant> candidates = new LinkedHashMap<>();
        candidates.put(1L, occurredAt);
        candidates.put(2L, null);

        userSummaryService.recordLatestPurchaseBatch(candidates);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(), any(Object[].class));
        Assertions.assertThat(sql.getValue())
                .contains("UPDATE user_summary SET last_purchase_at = CASE user_id")
                .contains("last_purchase_at IS NULL OR last_purchase_at < ?");
        verify(userSummaryRepository, never()).advanceLastPurchaseAt(org.mockito.ArgumentMatchers.anyLong(), any());
    }

    @Test
    @DisplayName("배치 projection은 요약이 없는 사용자를 기존 오류로 거부한다")
    @SuppressWarnings("unchecked")
    void recordLatestPurchaseBatchThrowsIfSummaryMissing() {
        doReturn(List.of()).when(jdbcTemplate).query(
                anyString(), org.mockito.ArgumentMatchers.<RowMapper<Long>>any(), any(Object[].class));

        assertThatThrownBy(() -> userSummaryService.recordLatestPurchaseBatch(
                Map.of(42L, Instant.parse("2026-08-01T00:00:00Z"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("User summary not found: 42");

        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("recordLogin은 사용자에 대한 마지막 로그인 시각을 갱신한다")
    void recordLoginUpdatesLastLogin() {
        Instant loggedAt = Instant.now();
        User user = mock(User.class);
        when(userRepository.findById(eq(1L))).thenReturn(Optional.of(user));

        userSummaryService.recordLogin(1L, loggedAt);

        verify(user).recordLogin(eq(loggedAt));
    }

    @Test
    @DisplayName("recordLogin은 사용자가 존재하지 않을 경우 예외를 던진다")
    void recordLoginThrowsIfUserNotFound() {
        when(userRepository.findById(eq(3L))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userSummaryService.recordLogin(3L, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("User not found: 3");
    }

    @Test
    @DisplayName("구매 요약 재구성은 가장 최근 CONFIRMED 구매 시각만 반영한다")
    void rebuildPurchaseSummaryUsesLatestConfirmedPurchase() {
        User user = mock(User.class);
        com.axon.core_service.domain.user.UserSummary summary = mock(com.axon.core_service.domain.user.UserSummary.class);
        Purchase purchase = mock(Purchase.class);
        when(userSummaryRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(summary));
        when(purchaseRepository.findFirstByUserIdAndStatusOrderByPurchaseAtDesc(1L, PurchaseStatus.CONFIRMED))
                .thenReturn(Optional.of(purchase));
        when(purchase.getPurchaseAt()).thenReturn(java.time.LocalDateTime.of(2026, 7, 28, 10, 0));

        userSummaryService.rebuildPurchaseSummary(1L);

        verify(summary).updateLastPurchaseAt(eq(java.time.LocalDateTime.of(2026, 7, 28, 1, 0)
                .toInstant(java.time.ZoneOffset.UTC)));
    }
}
