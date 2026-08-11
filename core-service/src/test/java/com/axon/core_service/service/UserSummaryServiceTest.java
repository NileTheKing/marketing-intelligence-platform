package com.axon.core_service.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.axon.core_service.domain.user.User;
import com.axon.core_service.repository.UserRepository;
import com.axon.core_service.repository.PurchaseRepository;
import com.axon.core_service.repository.UserSummaryRepository;
import com.axon.core_service.domain.purchase.Purchase;
import com.axon.core_service.domain.purchase.PurchaseStatus;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserSummaryServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PurchaseRepository purchaseRepository;

    @Mock
    private UserSummaryRepository userSummaryRepository;

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
    @DisplayName("배치 projection은 마지막 구매 시각만 전달하고 기존 값보다 과거로 갱신하지 않는다")
    void recordLatestPurchaseBatchAdvancesSummary() {
        Instant olderMessage = Instant.parse("2026-08-01T00:00:00Z");
        java.time.LocalDateTime candidate = java.time.LocalDateTime.ofInstant(
                olderMessage, java.time.ZoneId.of("Asia/Seoul"));
        when(userSummaryRepository.advanceLastPurchaseAt(1L, candidate)).thenReturn(1);

        userSummaryService.recordLatestPurchaseBatch(java.util.Map.of(1L, olderMessage));

        verify(userSummaryRepository).advanceLastPurchaseAt(1L, candidate);
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
