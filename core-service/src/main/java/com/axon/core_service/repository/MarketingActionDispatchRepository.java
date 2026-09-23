package com.axon.core_service.repository;

import com.axon.core_service.domain.marketing.MarketingActionDispatch;
import com.axon.core_service.domain.marketing.MarketingActionExecutionStatus;
import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface MarketingActionDispatchRepository extends JpaRepository<MarketingActionDispatch, Long> {

    Optional<MarketingActionDispatch> findTopByExecution_IdOrderBySequenceDesc(Long executionId);

    List<MarketingActionDispatch> findAllByExecution_IdOrderBySequenceAsc(Long executionId);

    @Query("select d from MarketingActionDispatch d join fetch d.execution where d.execution.actionId = :actionId "
            + "and d.dltAt >= :since order by d.dltAt desc")
    List<MarketingActionDispatch> findFailureHistoryByActionSince(@Param("actionId") Long actionId,
                                                                    @Param("since") LocalDateTime since);

    @Query("select d from MarketingActionDispatch d join fetch d.execution where d.id = :id")
    Optional<MarketingActionDispatch> findByIdWithExecution(@Param("id") Long id);

    @Query("select d from MarketingActionDispatch d join fetch d.execution e "
            + "where d.status = :failed "
            + "and d.sequence = (select max(newer.sequence) from MarketingActionDispatch newer "
            + "where newer.execution = d.execution) order by d.dltAt desc")
    List<MarketingActionDispatch> findLatestFinalFailures(
            @Param("failed") MarketingActionExecutionStatus failed);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update MarketingActionDispatch d set d.status = :dispatching "
            + "where d.id = :id and d.status = :pending")
    int markDispatching(@Param("id") Long id,
                        @Param("pending") MarketingActionExecutionStatus pending,
                        @Param("dispatching") MarketingActionExecutionStatus dispatching);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update MarketingActionDispatch d set d.status = :dispatched, d.dispatchedAt = :dispatchedAt "
            + "where d.id = :id and d.status = :dispatching")
    int markDispatched(@Param("id") Long id,
                       @Param("dispatching") MarketingActionExecutionStatus dispatching,
                       @Param("dispatched") MarketingActionExecutionStatus dispatched,
                       @Param("dispatchedAt") LocalDateTime dispatchedAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update MarketingActionDispatch d set d.status = case when d.attemptCount = 0 "
            + "then :processing else :retrying end, d.attemptCount = d.attemptCount + 1 "
            + "where d.id = :id and d.status in (:dispatching, :dispatched, :processing, :retrying)")
    int recordAttempt(@Param("id") Long id,
                      @Param("dispatching") MarketingActionExecutionStatus dispatching,
                      @Param("dispatched") MarketingActionExecutionStatus dispatched,
                      @Param("processing") MarketingActionExecutionStatus processing,
                      @Param("retrying") MarketingActionExecutionStatus retrying);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update MarketingActionDispatch d set d.status = :succeeded, d.completedAt = :completedAt, "
            + "d.lastFailureReason = null where d.id = :id and d.status in (:processing, :retrying)")
    int markSucceeded(@Param("id") Long id,
                      @Param("processing") MarketingActionExecutionStatus processing,
                      @Param("retrying") MarketingActionExecutionStatus retrying,
                      @Param("succeeded") MarketingActionExecutionStatus succeeded,
                      @Param("completedAt") LocalDateTime completedAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update MarketingActionDispatch d set d.status = :failed, d.lastFailureReason = :reason, "
            + "d.completedAt = :completedAt where d.id = :id and d.status = :dispatching")
    int markDispatchFailed(@Param("id") Long id,
                           @Param("dispatching") MarketingActionExecutionStatus dispatching,
                           @Param("failed") MarketingActionExecutionStatus failed,
                           @Param("reason") String reason,
                           @Param("completedAt") LocalDateTime completedAt);

    @Modifying
    @Transactional
    @Query("update MarketingActionDispatch d set d.status = :failed, d.lastFailureReason = :reason, "
            + "d.completedAt = :completedAt, d.dltAt = :dltAt where d.id = :id "
            + "and d.status in (:dispatching, :dispatched, :processing, :retrying)")
    int markDltFinal(@Param("id") Long id,
                     @Param("dispatching") MarketingActionExecutionStatus dispatching,
                     @Param("dispatched") MarketingActionExecutionStatus dispatched,
                     @Param("processing") MarketingActionExecutionStatus processing,
                     @Param("retrying") MarketingActionExecutionStatus retrying,
                     @Param("failed") MarketingActionExecutionStatus failed,
                     @Param("reason") String reason,
                     @Param("completedAt") LocalDateTime completedAt,
                     @Param("dltAt") LocalDateTime dltAt);
}
