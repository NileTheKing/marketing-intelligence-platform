package com.axon.core_service.scheduler;

import com.axon.core_service.domain.purchase.Purchase;
import com.axon.core_service.domain.purchase.PurchaseType;
import com.axon.core_service.repository.PurchaseRepository;
import com.axon.core_service.repository.UserSummaryPurchaseMismatch;
import com.axon.core_service.repository.UserSummaryRepository;
import com.axon.core_service.observability.CorePipelineMetrics;
import com.axon.core_service.service.reconciliation.ReconciliationIssueService;
import com.axon.core_service.service.UserSummaryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

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

    @Mock
    private UserSummaryRepository userSummaryRepository;

    @Mock
    private CorePipelineMetrics pipelineMetrics;

    @Mock
    private ReconciliationIssueService reconciliationIssueService;

    @Mock
    private UserSummaryService userSummaryService;

    @Mock
    private SchedulerExecutionLock schedulerExecutionLock;

    @Mock
    private TransactionTemplate transactionTemplate;

    @InjectMocks
    private ReconciliationScheduler reconciliationScheduler;

    @Test
    @DisplayName("Ghost Purchase가 없을 때 정상적으로 로깅만 하고 리턴한다")
    void detectGhostPurchases_NoGhosts() {
        // Given
        when(purchaseRepository.findGhostPurchases(any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(Collections.emptyList());
        runSchedulerTask();

        // When
        reconciliationScheduler.detectGhostPurchases();

        // Then
        verify(purchaseRepository, times(1))
                .findGhostPurchases(any(LocalDateTime.class), any(LocalDateTime.class));
        verify(pipelineMetrics).recordReconciliationResult(0);
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
        runSchedulerTask();

        // When
        reconciliationScheduler.detectGhostPurchases();

        // Then
        verify(purchaseRepository, times(1))
                .findGhostPurchases(any(LocalDateTime.class), any(LocalDateTime.class));
        verify(pipelineMetrics).recordReconciliationResult(1);
        verify(reconciliationIssueService).detectGhostPurchase(ghostPurchase);
        // 로깅 로직 수행 중 NPE 등 크러시가 발생하지 않는지 검증합니다.
    }

    @Test
    @DisplayName("UserSummary 불일치를 발견하면 원장 기준으로 복구하고 issue를 남기지 않는다")
    void reconcileUserSummaries_RepairsMismatch() {
        UserSummaryPurchaseMismatch mismatch = mismatchWithUserId(7L);
        runSchedulerTask();
        when(userSummaryRepository.findPurchaseSummaryMismatches()).thenReturn(List.of(mismatch));

        reconciliationScheduler.detectGhostPurchases();

        verify(userSummaryService).rebuildPurchaseSummary(7L);
        verify(reconciliationIssueService).resolveUserSummaryMismatch(7L);
        verify(reconciliationIssueService, never()).detectUserSummaryMismatch(any(), any(), any());
        verify(pipelineMetrics).recordUserSummaryReconciliationResult(1);
        verify(pipelineMetrics).recordUserSummaryRepair(true);
    }

    @Test
    @DisplayName("UserSummary 복구 실패 시 사용자별 reconciliation issue를 upsert한다")
    void reconcileUserSummaries_RecordsIssueWhenRepairFails() {
        LocalDateTime expected = LocalDateTime.of(2026, 9, 1, 12, 0);
        LocalDateTime observed = null;
        UserSummaryPurchaseMismatch mismatch = mismatch(8L, expected, observed);
        runSchedulerTask();
        when(userSummaryRepository.findPurchaseSummaryMismatches()).thenReturn(List.of(mismatch));
        doThrow(new IllegalStateException("summary unavailable"))
                .when(userSummaryService).rebuildPurchaseSummary(8L);

        reconciliationScheduler.detectGhostPurchases();

        verify(reconciliationIssueService).detectUserSummaryMismatch(8L, expected, observed);
        verify(reconciliationIssueService, never()).resolveUserSummaryMismatch(any());
        verify(pipelineMetrics).recordUserSummaryRepair(false);
    }

    private UserSummaryPurchaseMismatch mismatch(Long userId, LocalDateTime expected, LocalDateTime observed) {
        UserSummaryPurchaseMismatch mismatch = mock(UserSummaryPurchaseMismatch.class);
        when(mismatch.getUserId()).thenReturn(userId);
        when(mismatch.getExpectedLastPurchaseAt()).thenReturn(expected);
        when(mismatch.getObservedLastPurchaseAt()).thenReturn(observed);
        return mismatch;
    }

    private UserSummaryPurchaseMismatch mismatchWithUserId(Long userId) {
        UserSummaryPurchaseMismatch mismatch = mock(UserSummaryPurchaseMismatch.class);
        when(mismatch.getUserId()).thenReturn(userId);
        return mismatch;
    }

    private void runSchedulerTask() {
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(1)).run();
            return true;
        }).when(schedulerExecutionLock).runIfAcquired(any(), any());
        doAnswer(invocation -> {
            invocation.getArgument(0, java.util.function.Consumer.class)
                    .accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(pipelineMetrics).recordReconciliationScan(any());
    }
}
