package com.axon.core_service.repository;

import com.axon.core_service.domain.campaignactivity.CampaignActivityRunTarget;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CampaignActivityRunTargetRepository extends JpaRepository<CampaignActivityRunTarget, Long> {

    List<CampaignActivityRunTarget> findAllByRunIdAndExecutionIdIsNullOrderByUserIdAsc(Long runId);

    long countByRunId(Long runId);
}
