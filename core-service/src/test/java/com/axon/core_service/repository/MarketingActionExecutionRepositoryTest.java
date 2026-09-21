package com.axon.core_service.repository;

import com.axon.core_service.domain.marketing.MarketingActionExecution;
import com.axon.core_service.domain.marketing.MarketingActionExecutionStatus;
import com.axon.core_service.domain.marketing.RewardType;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class MarketingActionExecutionRepositoryTest {

    @Autowired
    private MarketingActionExecutionRepository executionRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void lateDispatchCallbackCannotOverwriteSucceededExecution() {
        MarketingActionExecution execution = executionRepository.saveAndFlush(execution());
        Long id = execution.getId();

        assertThat(executionRepository.markDispatching(id, 1L,
                MarketingActionExecutionStatus.PENDING, MarketingActionExecutionStatus.DISPATCHING)).isEqualTo(1);
        assertThat(executionRepository.markDispatched(id, 1L,
                MarketingActionExecutionStatus.DISPATCHING, MarketingActionExecutionStatus.DISPATCHED,
                LocalDateTime.now())).isEqualTo(1);
        assertThat(executionRepository.recordAttempt(id, 1L,
                MarketingActionExecutionStatus.DISPATCHING,
                MarketingActionExecutionStatus.DISPATCHED, MarketingActionExecutionStatus.PROCESSING,
                MarketingActionExecutionStatus.RETRYING)).isEqualTo(1);
        assertThat(executionRepository.markSucceeded(id, 1L,
                MarketingActionExecutionStatus.PROCESSING, MarketingActionExecutionStatus.RETRYING,
                MarketingActionExecutionStatus.SUCCEEDED, LocalDateTime.now())).isEqualTo(1);

        assertThat(executionRepository.markDispatched(id, 1L,
                MarketingActionExecutionStatus.DISPATCHING, MarketingActionExecutionStatus.DISPATCHED,
                LocalDateTime.now())).isZero();

        entityManager.clear();
        assertThat(executionRepository.findById(id).orElseThrow().getStatus())
                .isEqualTo(MarketingActionExecutionStatus.SUCCEEDED);
    }

    @Test
    void dltUpdateIsIdempotentAndDoesNotChangeTerminalFailureAgain() {
        MarketingActionExecution execution = executionRepository.saveAndFlush(execution());
        Long id = execution.getId();
        LocalDateTime dltAt = LocalDateTime.now();

        executionRepository.markDispatching(id, 1L,
                MarketingActionExecutionStatus.PENDING, MarketingActionExecutionStatus.DISPATCHING);
        executionRepository.markDispatched(id, 1L,
                MarketingActionExecutionStatus.DISPATCHING, MarketingActionExecutionStatus.DISPATCHED, dltAt);
        executionRepository.recordAttempt(id, 1L,
                MarketingActionExecutionStatus.DISPATCHING,
                MarketingActionExecutionStatus.DISPATCHED, MarketingActionExecutionStatus.PROCESSING,
                MarketingActionExecutionStatus.RETRYING);

        assertThat(executionRepository.markDltFinal(id, 1L,
                MarketingActionExecutionStatus.DISPATCHING,
                MarketingActionExecutionStatus.DISPATCHED, MarketingActionExecutionStatus.PROCESSING,
                MarketingActionExecutionStatus.RETRYING, MarketingActionExecutionStatus.FAILED_FINAL,
                "timeout", dltAt, dltAt)).isEqualTo(1);
        assertThat(executionRepository.markDltFinal(id, 1L,
                MarketingActionExecutionStatus.DISPATCHING,
                MarketingActionExecutionStatus.DISPATCHED, MarketingActionExecutionStatus.PROCESSING,
                MarketingActionExecutionStatus.RETRYING, MarketingActionExecutionStatus.FAILED_FINAL,
                "timeout", dltAt.plusSeconds(1), dltAt.plusSeconds(1))).isZero();
    }

    private MarketingActionExecution execution() {
        return MarketingActionExecution.builder()
                .actionId(5L)
                .ruleId(10L)
                .actionReferenceId(99L)
                .userId(1L)
                .productId(100L)
                .channel(RewardType.WEBHOOK)
                .build();
    }

    @Test
    void consumerCanFinishWhileKafkaCallbackIsStillInDispatching() {
        MarketingActionExecution execution = executionRepository.saveAndFlush(execution());
        Long id = execution.getId();

        assertThat(executionRepository.markDispatching(id, 1L,
                MarketingActionExecutionStatus.PENDING, MarketingActionExecutionStatus.DISPATCHING)).isEqualTo(1);
        assertThat(executionRepository.recordAttempt(id, 1L,
                MarketingActionExecutionStatus.DISPATCHING, MarketingActionExecutionStatus.DISPATCHED,
                MarketingActionExecutionStatus.PROCESSING, MarketingActionExecutionStatus.RETRYING)).isEqualTo(1);
        assertThat(executionRepository.markSucceeded(id, 1L,
                MarketingActionExecutionStatus.PROCESSING, MarketingActionExecutionStatus.RETRYING,
                MarketingActionExecutionStatus.SUCCEEDED, LocalDateTime.now())).isEqualTo(1);

        assertThat(executionRepository.markDispatched(id, 1L,
                MarketingActionExecutionStatus.DISPATCHING, MarketingActionExecutionStatus.DISPATCHED,
                LocalDateTime.now())).isZero();

        entityManager.clear();
        assertThat(executionRepository.findById(id).orElseThrow().getStatus())
                .isEqualTo(MarketingActionExecutionStatus.SUCCEEDED);
    }

    @Test
    void dltConsumerCanFinishWhileKafkaCallbackIsStillInDispatching() {
        MarketingActionExecution execution = executionRepository.saveAndFlush(execution());
        Long id = execution.getId();
        LocalDateTime now = LocalDateTime.now();

        assertThat(executionRepository.markDispatching(id, 1L,
                MarketingActionExecutionStatus.PENDING, MarketingActionExecutionStatus.DISPATCHING)).isEqualTo(1);
        assertThat(executionRepository.markDltFinal(id, 1L,
                MarketingActionExecutionStatus.DISPATCHING, MarketingActionExecutionStatus.DISPATCHED,
                MarketingActionExecutionStatus.PROCESSING, MarketingActionExecutionStatus.RETRYING,
                MarketingActionExecutionStatus.FAILED_FINAL, "terminal failure", now, now)).isEqualTo(1);

        assertThat(executionRepository.markDispatched(id, 1L,
                MarketingActionExecutionStatus.DISPATCHING, MarketingActionExecutionStatus.DISPATCHED,
                LocalDateTime.now())).isZero();

        entityManager.clear();
        assertThat(executionRepository.findById(id).orElseThrow().getStatus())
                .isEqualTo(MarketingActionExecutionStatus.FAILED_FINAL);
    }
}
