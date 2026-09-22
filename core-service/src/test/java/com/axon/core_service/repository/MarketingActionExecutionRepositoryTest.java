package com.axon.core_service.repository;

import com.axon.core_service.domain.marketing.MarketingActionDispatch;
import com.axon.core_service.domain.marketing.MarketingActionDispatchInitiatedBy;
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
    private MarketingActionDispatchRepository dispatchRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void lateDispatchCallbackCannotOverwriteSucceededDispatch() {
        MarketingActionExecution execution = executionRepository.saveAndFlush(execution());
        MarketingActionDispatch dispatch = dispatchRepository.saveAndFlush(dispatch(execution, 1L));
        Long id = dispatch.getId();

        assertThat(dispatchRepository.markDispatching(id, MarketingActionExecutionStatus.PENDING,
                MarketingActionExecutionStatus.DISPATCHING)).isEqualTo(1);
        assertThat(dispatchRepository.markDispatched(id, MarketingActionExecutionStatus.DISPATCHING,
                MarketingActionExecutionStatus.DISPATCHED, LocalDateTime.now())).isEqualTo(1);
        assertThat(dispatchRepository.recordAttempt(id, MarketingActionExecutionStatus.DISPATCHING,
                MarketingActionExecutionStatus.DISPATCHED, MarketingActionExecutionStatus.PROCESSING,
                MarketingActionExecutionStatus.RETRYING)).isEqualTo(1);
        assertThat(dispatchRepository.markSucceeded(id, MarketingActionExecutionStatus.PROCESSING,
                MarketingActionExecutionStatus.RETRYING, MarketingActionExecutionStatus.SUCCEEDED,
                LocalDateTime.now())).isEqualTo(1);

        assertThat(dispatchRepository.markDispatched(id, MarketingActionExecutionStatus.DISPATCHING,
                MarketingActionExecutionStatus.DISPATCHED, LocalDateTime.now())).isZero();

        entityManager.clear();
        assertThat(dispatchRepository.findById(id).orElseThrow().getStatus())
                .isEqualTo(MarketingActionExecutionStatus.SUCCEEDED);
    }

    @Test
    void oldFinalFailureRemainsWhenNewDispatchIsCreated() {
        MarketingActionExecution execution = executionRepository.saveAndFlush(execution());
        MarketingActionDispatch first = dispatchRepository.saveAndFlush(dispatch(execution, 1L));
        LocalDateTime dltAt = LocalDateTime.now();
        dispatchRepository.markDispatching(first.getId(), MarketingActionExecutionStatus.PENDING,
                MarketingActionExecutionStatus.DISPATCHING);
        dispatchRepository.markDltFinal(first.getId(), MarketingActionExecutionStatus.DISPATCHING,
                MarketingActionExecutionStatus.DISPATCHED, MarketingActionExecutionStatus.PROCESSING,
                MarketingActionExecutionStatus.RETRYING, MarketingActionExecutionStatus.FAILED_FINAL,
                "timeout", dltAt, dltAt);

        MarketingActionDispatch second = dispatchRepository.saveAndFlush(dispatch(execution, 2L));
        assertThat(dispatchRepository.findTopByExecution_IdOrderBySequenceDesc(execution.getId()).orElseThrow().getId())
                .isEqualTo(second.getId());
        entityManager.clear();
        assertThat(dispatchRepository.findById(first.getId()).orElseThrow().getLastFailureReason())
                .isEqualTo("timeout");
    }

    @Test
    void latestFinalFailureQueryExcludesExecutionWithLaterDispatch() {
        MarketingActionExecution execution = executionRepository.saveAndFlush(execution());
        MarketingActionDispatch first = dispatchRepository.saveAndFlush(dispatch(execution, 1L));
        dispatchRepository.markDispatching(first.getId(), MarketingActionExecutionStatus.PENDING,
                MarketingActionExecutionStatus.DISPATCHING);
        dispatchRepository.markDltFinal(first.getId(), MarketingActionExecutionStatus.DISPATCHING,
                MarketingActionExecutionStatus.DISPATCHED, MarketingActionExecutionStatus.PROCESSING,
                MarketingActionExecutionStatus.RETRYING, MarketingActionExecutionStatus.FAILED_FINAL,
                "failure", LocalDateTime.now(), LocalDateTime.now());
        dispatchRepository.saveAndFlush(dispatch(execution, 2L));

        assertThat(dispatchRepository.findLatestFinalFailures(MarketingActionExecutionStatus.FAILED_FINAL))
                .isEmpty();
    }

    @Test
    void duplicateOldDltCannotChangeNewDispatch() {
        MarketingActionExecution execution = executionRepository.saveAndFlush(execution());
        MarketingActionDispatch first = dispatchRepository.saveAndFlush(dispatch(execution, 1L));
        dispatchRepository.markDispatching(first.getId(), MarketingActionExecutionStatus.PENDING,
                MarketingActionExecutionStatus.DISPATCHING);
        dispatchRepository.markDltFinal(first.getId(), MarketingActionExecutionStatus.DISPATCHING,
                MarketingActionExecutionStatus.DISPATCHED, MarketingActionExecutionStatus.PROCESSING,
                MarketingActionExecutionStatus.RETRYING, MarketingActionExecutionStatus.FAILED_FINAL,
                "old failure", LocalDateTime.now(), LocalDateTime.now());

        MarketingActionDispatch second = dispatchRepository.saveAndFlush(dispatch(execution, 2L));
        dispatchRepository.markDispatching(second.getId(), MarketingActionExecutionStatus.PENDING,
                MarketingActionExecutionStatus.DISPATCHING);

        assertThat(dispatchRepository.markDltFinal(first.getId(), MarketingActionExecutionStatus.DISPATCHING,
                MarketingActionExecutionStatus.DISPATCHED, MarketingActionExecutionStatus.PROCESSING,
                MarketingActionExecutionStatus.RETRYING, MarketingActionExecutionStatus.FAILED_FINAL,
                "duplicate old failure", LocalDateTime.now(), LocalDateTime.now())).isZero();

        entityManager.clear();
        assertThat(dispatchRepository.findById(second.getId()).orElseThrow().getStatus())
                .isEqualTo(MarketingActionExecutionStatus.DISPATCHING);
    }

    private MarketingActionExecution execution() {
        return MarketingActionExecution.builder()
                .actionId(5L).ruleId(10L).actionReferenceId(99L).userId(1L).productId(100L)
                .channel(RewardType.WEBHOOK).build();
    }

    private MarketingActionDispatch dispatch(MarketingActionExecution execution, long sequence) {
        return MarketingActionDispatch.builder()
                .execution(execution).sequence(sequence).initiatedBy(
                        sequence == 1 ? MarketingActionDispatchInitiatedBy.SYSTEM : MarketingActionDispatchInitiatedBy.ADMIN)
                .build();
    }
}
