package com.axon.core_service.service.reconciliation;

import com.axon.core_service.domain.reconciliation.ReconciliationIssue;
import com.axon.core_service.domain.reconciliation.ReconciliationIssueStatus;
import com.axon.core_service.domain.reconciliation.ReconciliationIssueType;
import com.axon.core_service.observability.CorePipelineMetrics;
import com.axon.core_service.repository.ReconciliationIssueRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReconciliationIssueServiceTest {

    @Mock
    private ReconciliationIssueRepository issueRepository;

    @Mock
    private CorePipelineMetrics pipelineMetrics;

    @InjectMocks
    private ReconciliationIssueService issueService;

    @Test
    void repeatedMismatchRefreshesOneOpenIssue() {
        ReconciliationIssue issue = ReconciliationIssue.open(
                ReconciliationIssueType.REDIS_PURCHASE_COUNT_MISMATCH,
                "REDIS_PURCHASE_COUNT_MISMATCH:1:-", 1L, null, null,
                10L, 9L, "initial", LocalDateTime.now().minusMinutes(1));

        when(issueRepository.findByFingerprint("REDIS_PURCHASE_COUNT_MISMATCH:1:-"))
                .thenReturn(Optional.empty(), Optional.of(issue));
        when(issueRepository.save(any(ReconciliationIssue.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(issueRepository.countByStatusAndIssueType(ReconciliationIssueStatus.OPEN,
                ReconciliationIssueType.REDIS_PURCHASE_COUNT_MISMATCH)).thenReturn(1L);

        issueService.detectRedisPurchaseCountMismatch(1L, 10L, 9L);
        ReconciliationIssue refreshed = issueService.detectRedisPurchaseCountMismatch(1L, 10L, 8L);

        assertThat(refreshed.getStatus()).isEqualTo(ReconciliationIssueStatus.OPEN);
        assertThat(refreshed.getObservedValue()).isEqualTo(8L);
        verify(issueRepository, times(1)).save(any(ReconciliationIssue.class));
        verify(pipelineMetrics, times(1)).recordReconciliationIssueDetection(
                eq(ReconciliationIssueType.REDIS_PURCHASE_COUNT_MISMATCH), eq(true), anyLong());
    }

    @Test
    void resolvedIssueReopensWhenMismatchRecurs() {
        ReconciliationIssue issue = ReconciliationIssue.open(
                ReconciliationIssueType.REDIS_PURCHASE_COUNT_MISMATCH,
                "REDIS_PURCHASE_COUNT_MISMATCH:1:-", 1L, null, null,
                10L, 9L, "initial", LocalDateTime.now().minusMinutes(1));
        issue.resolve("manually checked", LocalDateTime.now());

        when(issueRepository.findByFingerprint("REDIS_PURCHASE_COUNT_MISMATCH:1:-"))
                .thenReturn(Optional.of(issue));
        when(issueRepository.countByStatusAndIssueType(ReconciliationIssueStatus.OPEN,
                ReconciliationIssueType.REDIS_PURCHASE_COUNT_MISMATCH)).thenReturn(1L);

        ReconciliationIssue reopened = issueService.detectRedisPurchaseCountMismatch(1L, 12L, 10L);

        assertThat(reopened.getStatus()).isEqualTo(ReconciliationIssueStatus.OPEN);
        assertThat(reopened.getResolutionNote()).isNull();
        assertThat(reopened.getResolvedAt()).isNull();
        verify(pipelineMetrics).recordReconciliationIssueDetection(
                eq(ReconciliationIssueType.REDIS_PURCHASE_COUNT_MISMATCH), eq(true), anyLong());
    }

    @Test
    void acknowledgeAndResolveRequireNotesAndRefreshOpenCount() {
        ReconciliationIssue issue = ReconciliationIssue.open(
                ReconciliationIssueType.GHOST_PURCHASE,
                "GHOST_PURCHASE:1:2", 1L, 2L, 3L,
                null, null, "missing entry", LocalDateTime.now());
        when(issueRepository.findById(1L)).thenReturn(Optional.of(issue));
        when(issueRepository.countByStatusAndIssueType(ReconciliationIssueStatus.OPEN,
                ReconciliationIssueType.GHOST_PURCHASE)).thenReturn(0L);

        issueService.acknowledge(1L, "investigating");
        issueService.resolve(1L, "entry was repaired");

        assertThat(issue.getStatus()).isEqualTo(ReconciliationIssueStatus.RESOLVED);
        assertThat(issue.getResolutionNote()).isEqualTo("entry was repaired");
        assertThat(issue.getEvidence()).isEqualTo("missing entry");
        verify(pipelineMetrics, times(2)).setOpenReconciliationIssueCount(
                ReconciliationIssueType.GHOST_PURCHASE, 0L);
    }
}
