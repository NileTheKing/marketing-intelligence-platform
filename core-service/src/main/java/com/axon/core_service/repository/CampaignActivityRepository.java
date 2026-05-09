package com.axon.core_service.repository;

import com.axon.core_service.domain.campaignactivity.CampaignActivity;
import com.axon.core_service.domain.dto.campaignactivity.CampaignActivityStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CampaignActivityRepository extends JpaRepository<CampaignActivity, Long> {

    /**
     * Retrieves all CampaignActivity records associated with the Campaign
     * identified by the given id.
     *
     * @param campaignId the id of the Campaign whose activities should be retrieved
     * @return a List of CampaignActivity belonging to the Campaign with the
     *         specified id; an empty list if none are found
     */
    List<CampaignActivity> findAllByCampaign_Id(Long campaignId);

    List<CampaignActivity> findAllByStatus(
            com.axon.core_service.domain.dto.campaignactivity.CampaignActivityStatus status);

    /**
     * Finds campaigns that already ended and still have the given status.
     *
     * Used by CampaignStockSyncScheduler to catch every ended ACTIVE campaign
     * even if the scheduler execution is delayed.
     *
     * @param endTime campaigns ending on or before this time
     * @param status the campaign activity status to filter by
     * @return list of campaigns that already ended with the given status
     */
    List<CampaignActivity> findByEndDateBeforeAndStatus(LocalDateTime endTime, CampaignActivityStatus status);
}
