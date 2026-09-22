package com.axon.core_service.service;

import com.axon.core_service.domain.marketing.MarketingActionDispatch;
import com.axon.core_service.domain.marketing.MarketingActionDispatchInitiatedBy;
import com.axon.core_service.domain.marketing.MarketingActionExecution;
import com.axon.core_service.domain.marketing.MarketingActionExecutionStatus;
import com.axon.core_service.domain.marketing.RewardType;
import com.axon.core_service.exception.BusinessConflictException;
import com.axon.core_service.repository.MarketingActionDispatchRepository;
import com.axon.core_service.repository.MarketingActionExecutionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MarketingActionExecutionServiceTest {

    @Mock private MarketingActionExecutionRepository executionRepository;
    @Mock private MarketingActionDispatchRepository dispatchRepository;
    @Mock private KafkaTemplate<String, Object> kafkaTemplate;
    @Mock private MarketingActionExecutionRetryClaimService retryClaimService;

    @Test
    void approvedRetryCreatesNewDispatchAndPreservesPreviousDispatch() {
        MarketingActionExecution execution = execution(42L);
        MarketingActionDispatch first = dispatch(execution, 10L, 1L,
                MarketingActionDispatchInitiatedBy.SYSTEM, MarketingActionExecutionStatus.FAILED_FINAL);
        MarketingActionDispatch retry = dispatch(execution, 11L, 2L,
                MarketingActionDispatchInitiatedBy.ADMIN, MarketingActionExecutionStatus.PENDING);
        ReflectionTestUtils.setField(retry, "status", MarketingActionExecutionStatus.DISPATCHING);

        when(retryClaimService.claim(42L)).thenReturn(retry);
        when(dispatchRepository.markDispatched(eq(11L), eq(MarketingActionExecutionStatus.DISPATCHING),
                eq(MarketingActionExecutionStatus.DISPATCHED), any())).thenReturn(1);
        when(kafkaTemplate.send(eq("axon.campaign-activity.command"), any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        MarketingActionDispatch returned = new MarketingActionExecutionService(
                executionRepository, dispatchRepository, kafkaTemplate, retryClaimService).retryApproved(42L);

        assertThat(returned.getSequence()).isEqualTo(2L);
        assertThat(returned.getStatus()).isEqualTo(MarketingActionExecutionStatus.DISPATCHING);
        assertThat(first.getSequence()).isEqualTo(1L);
        assertThat(first.getStatus()).isEqualTo(MarketingActionExecutionStatus.FAILED_FINAL);
        verify(kafkaTemplate).send(eq("axon.campaign-activity.command"), any());
    }

    @Test
    void secondApprovalCannotCreateAnotherDispatchAfterFirstClaim() {
        MarketingActionExecution execution = execution(42L);
        MarketingActionDispatch claimed = dispatch(execution, 11L, 2L,
                MarketingActionDispatchInitiatedBy.ADMIN, MarketingActionExecutionStatus.DISPATCHING);
        when(retryClaimService.claim(42L)).thenThrow(new BusinessConflictException("already claimed"));

        MarketingActionExecutionService service = new MarketingActionExecutionService(
                executionRepository, dispatchRepository, kafkaTemplate, retryClaimService);

        assertThatThrownBy(() -> service.retryApproved(42L)).isInstanceOf(BusinessConflictException.class);
        verify(kafkaTemplate, never()).send(any(), any());
        verify(dispatchRepository, never()).saveAndFlush(any());
    }

    @Test
    void lateKafkaSuccessCallbackCannotOverwriteTerminalDispatch() {
        when(dispatchRepository.markDispatched(eq(11L), eq(MarketingActionExecutionStatus.DISPATCHING),
                eq(MarketingActionExecutionStatus.DISPATCHED), any())).thenReturn(0);

        MarketingActionExecutionService service = new MarketingActionExecutionService(
                executionRepository, dispatchRepository, kafkaTemplate, retryClaimService);

        assertThat(service.markDispatched(11L)).isFalse();
    }

    private MarketingActionExecution execution(Long id) {
        MarketingActionExecution execution = MarketingActionExecution.builder()
                .actionId(5L).ruleId(10L).actionReferenceId(99L).userId(1L).productId(100L)
                .channel(RewardType.COUPON).build();
        ReflectionTestUtils.setField(execution, "id", id);
        return execution;
    }

    private MarketingActionDispatch dispatch(MarketingActionExecution execution, Long id, long sequence,
                                              MarketingActionDispatchInitiatedBy initiatedBy,
                                              MarketingActionExecutionStatus status) {
        MarketingActionDispatch dispatch = MarketingActionDispatch.builder()
                .execution(execution).sequence(sequence).initiatedBy(initiatedBy).build();
        ReflectionTestUtils.setField(dispatch, "id", id);
        ReflectionTestUtils.setField(dispatch, "status", status);
        return dispatch;
    }
}
