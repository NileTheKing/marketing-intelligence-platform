package com.axon.core_service.repository;

import com.axon.core_service.domain.marketing.MarketingActionTriageCase;
import com.axon.core_service.domain.marketing.MarketingActionTriageStatus;
import com.axon.messaging.MarketingActionFailureCategory;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface MarketingActionTriageCaseRepository extends JpaRepository<MarketingActionTriageCase, Long> {

    Optional<MarketingActionTriageCase> findByDispatch_Id(Long dispatchId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from MarketingActionTriageCase c "
            + "where c.status = :pending or (c.status = :analyzing and c.analysisClaimExpiresAt < :now) "
            + "order by c.createdAt asc")
    List<MarketingActionTriageCase> findClaimCandidates(
            @Param("pending") MarketingActionTriageStatus pending,
            @Param("analyzing") MarketingActionTriageStatus analyzing,
            @Param("now") LocalDateTime now,
            org.springframework.data.domain.Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from MarketingActionTriageCase c where c.id = :id")
    Optional<MarketingActionTriageCase> findByIdForUpdate(@Param("id") Long id);

    @Query("select c.failureCategory as category, count(c) as count from MarketingActionTriageCase c "
            + "join c.dispatch d join d.execution e "
            + "where e.actionId = :actionId and c.createdAt >= :since "
            + "group by c.failureCategory order by count(c) desc")
    List<FailureCategoryCount> countFailuresByActionSince(@Param("actionId") Long actionId,
                                                           @Param("since") LocalDateTime since);

    interface FailureCategoryCount {
        MarketingActionFailureCategory getCategory();
        long getCount();
    }
}
