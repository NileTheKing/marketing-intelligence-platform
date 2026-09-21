package com.axon.core_service.service;

import com.axon.core_service.domain.marketing.MarketingActionExecution;
import com.axon.core_service.domain.marketing.MarketingActionExecutionStatus;
import com.axon.core_service.domain.marketing.RewardType;
import com.axon.core_service.exception.BusinessConflictException;
import com.axon.core_service.repository.MarketingActionExecutionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MarketingActionExecutionServiceTest {

    @Mock private MarketingActionExecutionRepository executionRepository;
    @Mock private KafkaTemplate<String, Object> kafkaTemplate;

    @Test
    void approvedRetryClaimsOnceAndRepublishesStoredPayload() {
        MarketingActionExecution execution = execution();
        when(executionRepository.claimFinalFailureForRetry(
                eq(42L), eq(MarketingActionExecutionStatus.FAILED_FINAL), eq(MarketingActionExecutionStatus.DISPATCHING)))
                .thenReturn(1);
        when(executionRepository.findById(42L)).thenReturn(Optional.of(execution));
        when(executionRepository.markDispatched(
                eq(42L), anyLong(), eq(MarketingActionExecutionStatus.DISPATCHING),
                eq(MarketingActionExecutionStatus.DISPATCHED), any()))
                .thenReturn(1);
        when(kafkaTemplate.send(eq("axon.campaign-activity.command"), any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        MarketingActionExecutionService service = new MarketingActionExecutionService(executionRepository, kafkaTemplate);

        service.retryApproved(42L);

        verify(kafkaTemplate).send(eq("axon.campaign-activity.command"), any());
        verify(executionRepository).claimFinalFailureForRetry(
                42L, MarketingActionExecutionStatus.FAILED_FINAL, MarketingActionExecutionStatus.DISPATCHING);
        verify(executionRepository).markDispatched(
                eq(42L), anyLong(), eq(MarketingActionExecutionStatus.DISPATCHING),
                eq(MarketingActionExecutionStatus.DISPATCHED), any());
    }

    @Test
    void secondApprovalCannotClaimAnAlreadyClaimedExecution() {
        MarketingActionExecution execution = execution();
        when(executionRepository.claimFinalFailureForRetry(anyLong(), any(), any())).thenReturn(0);
        when(executionRepository.findById(42L)).thenReturn(Optional.of(execution));

        MarketingActionExecutionService service = new MarketingActionExecutionService(executionRepository, kafkaTemplate);

        assertThatThrownBy(() -> service.retryApproved(42L))
                .isInstanceOf(BusinessConflictException.class);
        verify(kafkaTemplate, never()).send(any(), any());
    }

    @Test
    void lateKafkaSuccessCallbackCannotOverwriteTerminalState() {
        when(executionRepository.markDispatched(
                eq(42L), eq(1L), eq(MarketingActionExecutionStatus.DISPATCHING),
                eq(MarketingActionExecutionStatus.DISPATCHED), any()))
                .thenReturn(0);

        MarketingActionExecutionService service = new MarketingActionExecutionService(executionRepository, kafkaTemplate);

        org.assertj.core.api.Assertions.assertThat(service.markDispatched(42L, 1L)).isFalse();
        verify(executionRepository).markDispatched(
                eq(42L), eq(1L), eq(MarketingActionExecutionStatus.DISPATCHING),
                eq(MarketingActionExecutionStatus.DISPATCHED), org.mockito.ArgumentMatchers.any());
    }

    private MarketingActionExecution execution() {
        MarketingActionExecution execution = MarketingActionExecution.builder()
                .actionId(5L)
                .ruleId(10L)
                .actionReferenceId(99L)
                .userId(1L)
                .productId(100L)
                .channel(RewardType.COUPON)
                .build();
        ReflectionTestUtils.setField(execution, "id", 42L);
        ReflectionTestUtils.setField(execution, "status", MarketingActionExecutionStatus.FAILED_FINAL);
        return execution;
    }
}
