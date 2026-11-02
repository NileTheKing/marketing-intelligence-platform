package com.axon.core_service.commandprocessing;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.axon.core_service.service.UserSummaryService;
import com.axon.core_service.service.reconciliation.ReconciliationIssueService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class UserSummaryRecoveryConsumerTest {
    private final UserSummaryService summaries = mock(UserSummaryService.class);
    private final ReconciliationIssueService issues = mock(ReconciliationIssueService.class);
    private final UserSummaryRecoveryConsumer consumer =
            new UserSummaryRecoveryConsumer(new ObjectMapper(), summaries, issues);

    @Test
    void rebuildsEachUserOnceBeforeResolvingTheIssue() {
        consumer.consume("""
                {"schemaVersion":1,"targets":[{"userId":7},{"userId":7},{"userId":8}]}
                """);
        InOrder order = inOrder(summaries, issues);
        order.verify(summaries).rebuildPurchaseSummary(7L);
        order.verify(issues).resolveUserSummaryMismatch(7L);
        order.verify(summaries).rebuildPurchaseSummary(8L);
        order.verify(issues).resolveUserSummaryMismatch(8L);
        verifyNoMoreInteractions(summaries, issues);
    }

    @Test
    void failedRebuildIsRetriedWithoutResolvingIssue() {
        doThrow(new IllegalStateException("database unavailable")).when(summaries).rebuildPurchaseSummary(7L);
        assertThatThrownBy(() -> consumer.consume("{\"schemaVersion\":1,\"targets\":[{\"userId\":7}]}"))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(issues);
    }

    @Test
    void rejectsInvalidMessages() {
        assertThatThrownBy(() -> consumer.consume("{\"schemaVersion\":1,\"targets\":[{\"userId\":null}]}"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(summaries, issues);
    }
}
