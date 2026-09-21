package com.axon.core_service.repository;

import com.axon.core_service.domain.marketing.MarketingActionExecution;
import com.axon.core_service.domain.marketing.MarketingActionExecutionStatus;
import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.time.LocalDateTime;

public interface MarketingActionExecutionRepository extends JpaRepository<MarketingActionExecution, Long> {

    List<MarketingActionExecution> findAllByStatusOrderByDltAtDesc(MarketingActionExecutionStatus status);

    @Modifying
    @Transactional
    @Query("update MarketingActionExecution e set e.status = :dispatching "
            + "where e.id = :id and e.dispatchVersion = :version and e.status = :pending")
    int markDispatching(@Param("id") Long id, @Param("version") Long version,
                        @Param("pending") MarketingActionExecutionStatus pending,
                        @Param("dispatching") MarketingActionExecutionStatus dispatching);

    @Modifying
    @Transactional
    @Query("update MarketingActionExecution e set e.status = :dispatched, e.dispatchedAt = :dispatchedAt "
            + "where e.id = :id and e.dispatchVersion = :version and e.status = :dispatching")
    int markDispatched(@Param("id") Long id, @Param("version") Long version,
                       @Param("dispatching") MarketingActionExecutionStatus dispatching,
                       @Param("dispatched") MarketingActionExecutionStatus dispatched,
                       @Param("dispatchedAt") LocalDateTime dispatchedAt);

    @Modifying
    @Transactional
    @Query("update MarketingActionExecution e set e.status = case when e.attemptCount = 0 "
            + "then :processing else :retrying end, e.attemptCount = e.attemptCount + 1 "
            + "where e.id = :id and e.dispatchVersion = :version "
            + "and e.status in (:dispatching, :dispatched, :processing, :retrying)")
    int recordAttempt(@Param("id") Long id, @Param("version") Long version,
                      @Param("dispatching") MarketingActionExecutionStatus dispatching,
                      @Param("dispatched") MarketingActionExecutionStatus dispatched,
                      @Param("processing") MarketingActionExecutionStatus processing,
                      @Param("retrying") MarketingActionExecutionStatus retrying);

    @Modifying
    @Transactional
    @Query("update MarketingActionExecution e set e.status = :succeeded, e.completedAt = :completedAt, "
            + "e.lastFailureReason = null where e.id = :id and e.dispatchVersion = :version "
            + "and e.status in (:processing, :retrying)")
    int markSucceeded(@Param("id") Long id, @Param("version") Long version,
                      @Param("processing") MarketingActionExecutionStatus processing,
                      @Param("retrying") MarketingActionExecutionStatus retrying,
                      @Param("succeeded") MarketingActionExecutionStatus succeeded,
                      @Param("completedAt") LocalDateTime completedAt);

    @Modifying
    @Transactional
    @Query("update MarketingActionExecution e set e.status = :failed, e.lastFailureReason = :reason, "
            + "e.completedAt = :completedAt where e.id = :id and e.dispatchVersion = :version "
            + "and e.status = :dispatching")
    int markDispatchFailed(@Param("id") Long id, @Param("version") Long version,
                           @Param("dispatching") MarketingActionExecutionStatus dispatching,
                           @Param("failed") MarketingActionExecutionStatus failed,
                           @Param("reason") String reason, @Param("completedAt") LocalDateTime completedAt);

    @Modifying
    @Transactional
    @Query("update MarketingActionExecution e set e.status = :failed, e.lastFailureReason = :reason, "
            + "e.completedAt = :completedAt, e.dltAt = :dltAt where e.id = :id "
            + "and e.dispatchVersion = :version and e.status in (:dispatching, :dispatched, :processing, :retrying)")
    int markDltFinal(@Param("id") Long id, @Param("version") Long version,
                     @Param("dispatching") MarketingActionExecutionStatus dispatching,
                     @Param("dispatched") MarketingActionExecutionStatus dispatched,
                     @Param("processing") MarketingActionExecutionStatus processing,
                     @Param("retrying") MarketingActionExecutionStatus retrying,
                     @Param("failed") MarketingActionExecutionStatus failed,
                     @Param("reason") String reason, @Param("completedAt") LocalDateTime completedAt,
                     @Param("dltAt") LocalDateTime dltAt);

    @Modifying
    @Transactional
    @Query("update MarketingActionExecution e set e.status = :dispatching, "
            + "e.dispatchVersion = e.dispatchVersion + 1, e.completedAt = null, "
            + "e.dltAt = null, e.lastFailureReason = null "
            + "where e.id = :id and e.status = :failed")
    int claimFinalFailureForRetry(@Param("id") Long id,
                                  @Param("failed") MarketingActionExecutionStatus failed,
                                  @Param("dispatching") MarketingActionExecutionStatus dispatching);
}
