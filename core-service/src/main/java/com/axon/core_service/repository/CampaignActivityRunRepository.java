package com.axon.core_service.repository;

import com.axon.core_service.domain.campaignactivity.CampaignActivityRun;
import com.axon.core_service.domain.campaignactivity.CampaignActivityRunStatus;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CampaignActivityRunRepository extends JpaRepository<CampaignActivityRun, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select run from CampaignActivityRun run join fetch run.campaignActivity where run.id = :id")
    Optional<CampaignActivityRun> findByIdForUpdate(@Param("id") Long id);

    @Query("select run from CampaignActivityRun run where run.campaignActivity.id = :activityId "
            + "order by run.createdAt desc")
    List<CampaignActivityRun> findByCampaignActivityIdOrderByCreatedAtDesc(@Param("activityId") Long activityId);

    @Query("select run from CampaignActivityRun run where run.status = :pending "
            + "or (run.status = :analyzing and run.analysisClaimExpiresAt < :now) order by run.createdAt asc")
    List<CampaignActivityRun> findAnalysisClaimCandidates(@Param("pending") CampaignActivityRunStatus pending,
                                                          @Param("analyzing") CampaignActivityRunStatus analyzing,
                                                          @Param("now") LocalDateTime now);

    @Query("select coalesce(sum(run.targetCount), 0) from CampaignActivityRun run "
            + "where run.campaignActivity.id = :activityId and run.status in :statuses")
    long sumReservedTargetCount(@Param("activityId") Long activityId,
                                @Param("statuses") List<CampaignActivityRunStatus> statuses);
}
