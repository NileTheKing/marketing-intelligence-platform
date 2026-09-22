package com.axon.core_service.service;

import com.axon.core_service.domain.marketing.MarketingActionDispatch;
import com.axon.core_service.domain.marketing.MarketingActionDispatchInitiatedBy;
import com.axon.core_service.domain.marketing.MarketingActionExecution;
import com.axon.core_service.domain.marketing.MarketingActionExecutionStatus;
import com.axon.core_service.domain.marketing.RewardType;
import com.axon.core_service.exception.BusinessConflictException;
import com.axon.core_service.repository.MarketingActionDispatchRepository;
import com.axon.core_service.repository.MarketingActionExecutionRepository;
import com.axon.messaging.dto.CampaignActivityKafkaProducerDto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DataJpaTest
@Import({MarketingActionExecutionService.class, MarketingActionExecutionRetryClaimService.class})
class MarketingActionExecutionRetryConcurrencyTest {

    @Autowired
    private MarketingActionExecutionRepository executionRepository;

    @Autowired
    private MarketingActionDispatchRepository dispatchRepository;

    @Autowired
    private MarketingActionExecutionService executionService;

    @MockitoBean
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentRetryApprovalsCreateOneDispatchAndPublishOnce() throws Exception {
        MarketingActionExecution execution = executionRepository.saveAndFlush(execution());
        MarketingActionDispatch first = dispatchRepository.saveAndFlush(dispatch(execution, 1L));
        dispatchRepository.markDispatching(first.getId(), MarketingActionExecutionStatus.PENDING,
                MarketingActionExecutionStatus.DISPATCHING);
        dispatchRepository.markDltFinal(first.getId(), MarketingActionExecutionStatus.DISPATCHING,
                MarketingActionExecutionStatus.DISPATCHED, MarketingActionExecutionStatus.PROCESSING,
                MarketingActionExecutionStatus.RETRYING, MarketingActionExecutionStatus.FAILED_FINAL,
                "initial failure", java.time.LocalDateTime.now(), java.time.LocalDateTime.now());

        when(kafkaTemplate.send(anyString(), any())).thenAnswer(invocation -> {
            CampaignActivityKafkaProducerDto message = invocation.getArgument(1);
            assertThat(message.getDispatchId()).isNotNull();
            assertThat(dispatchRepository.findByIdWithExecution(message.getDispatchId())).isPresent();
            return CompletableFuture.completedFuture(null);
        });

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Object> firstAttempt = executor.submit(() -> retryConcurrently(execution.getId(), ready, start));
            Future<Object> secondAttempt = executor.submit(() -> retryConcurrently(execution.getId(), ready, start));

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Object> outcomes = List.of(firstAttempt.get(10, TimeUnit.SECONDS),
                    secondAttempt.get(10, TimeUnit.SECONDS));

            assertThat(outcomes.stream().filter(MarketingActionDispatch.class::isInstance)).hasSize(1);
            assertThat(outcomes.stream().filter(BusinessConflictException.class::isInstance)).hasSize(1);
            assertThat(dispatchRepository.findAllByExecution_IdOrderBySequenceAsc(execution.getId()).stream()
                    .filter(dispatch -> dispatch.getSequence() == 2L)).hasSize(1);
            verify(kafkaTemplate, times(1)).send(anyString(), any());
        } finally {
            executor.shutdownNow();
        }
    }

    private Object retryConcurrently(Long executionId, CountDownLatch ready, CountDownLatch start)
            throws InterruptedException {
        ready.countDown();
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        try {
            return executionService.retryApproved(executionId);
        } catch (BusinessConflictException conflict) {
            return conflict;
        }
    }

    private MarketingActionExecution execution() {
        return MarketingActionExecution.builder()
                .actionId(5L).ruleId(10L).actionReferenceId(99L).userId(1L).productId(100L)
                .channel(RewardType.COUPON).build();
    }

    private MarketingActionDispatch dispatch(MarketingActionExecution execution, long sequence) {
        MarketingActionDispatch dispatch = MarketingActionDispatch.builder()
                .execution(execution).sequence(sequence).initiatedBy(MarketingActionDispatchInitiatedBy.SYSTEM)
                .build();
        return dispatch;
    }
}
