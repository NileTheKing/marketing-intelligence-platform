package com.axon.core_service.service.reconciliation;

import com.axon.core_service.AbstractIntegrationTest;
import com.axon.core_service.domain.reconciliation.ReconciliationIssue;
import com.axon.core_service.domain.reconciliation.ReconciliationIssueStatus;
import com.axon.core_service.repository.ReconciliationIssueRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class ReconciliationIssueServiceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ReconciliationIssueService issueService;

    @Autowired
    private ReconciliationIssueRepository issueRepository;

    @BeforeEach
    void clearIssues() {
        issueRepository.deleteAll();
    }

    @Test
    void persistsOneIssueForRepeatedMismatchAndReopensItAfterResolution() {
        ReconciliationIssue created = issueService.detectRedisPurchaseCountMismatch(1L, 10L, 9L);
        ReconciliationIssue refreshed = issueService.detectRedisPurchaseCountMismatch(1L, 10L, 8L);

        assertThat(issueRepository.count()).isOne();
        assertThat(refreshed.getId()).isEqualTo(created.getId());
        assertThat(refreshed.getObservedValue()).isEqualTo(8L);

        issueService.resolve(created.getId(), "manual review complete");
        ReconciliationIssue reopened = issueService.detectRedisPurchaseCountMismatch(1L, 11L, 8L);

        assertThat(reopened.getStatus()).isEqualTo(ReconciliationIssueStatus.OPEN);
        assertThat(reopened.getResolvedAt()).isNull();
        assertThat(issueRepository.count()).isOne();
    }
}
