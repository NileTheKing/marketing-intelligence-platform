package com.axon.core_service.scheduler;

import com.axon.core_service.domain.purchase.Purchase;
import com.axon.core_service.domain.purchase.PurchaseType;
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
import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReconciliationSchedulerTest {

    @Mock
    private PurchaseRepository purchaseRepository;

    @InjectMocks
    private ReconciliationScheduler reconciliationScheduler;

    @Test
    @DisplayName("Ghost Purchase가 없을 때 정상적으로 로깅만 하고 리턴한다")
    void detectGhostPurchases_NoGhosts() {
        // Given
        when(purchaseRepository.findGhostPurchases(any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(Collections.emptyList());

        // When
        reconciliationScheduler.detectGhostPurchases();

        // Then
        verify(purchaseRepository, times(1))
                .findGhostPurchases(any(LocalDateTime.class), any(LocalDateTime.class));
        // 동작 간 예외가 발생하지 않고 무사히 넘어감을 보장합니다.
    }

    @Test
    @DisplayName("Ghost Purchase 발견 시 에러 로그를 남기고 정상 처리한다")
    void detectGhostPurchases_GhostsDetected() {
        // Given
        Purchase ghostPurchase = Purchase.builder()
                .userId(1L)
                .productId(10L)
                .campaignActivityId(100L)
                .purchaseType(PurchaseType.CAMPAIGNACTIVITY)
                .price(BigDecimal.valueOf(1000))
                .purchasedAt(Instant.now())
                .build();

        when(purchaseRepository.findGhostPurchases(any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(List.of(ghostPurchase));

        // When
        reconciliationScheduler.detectGhostPurchases();

        // Then
        verify(purchaseRepository, times(1))
                .findGhostPurchases(any(LocalDateTime.class), any(LocalDateTime.class));
        // 로깅 로직 수행 중 NPE 등 크러시가 발생하지 않는지 검증합니다.
    }
}
