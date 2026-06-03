package com.axon.core_service.repository;

import com.axon.core_service.domain.campaignactivity.CampaignActivity;
import com.axon.core_service.domain.dto.campaignactivity.CampaignActivityStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    @EntityGraph(attributePaths = {"product", "coupon"})
    List<CampaignActivity> findAllByStatus(CampaignActivityStatus status);

    @EntityGraph(attributePaths = "campaign")
    @Query("SELECT ca FROM CampaignActivity ca WHERE ca.id = :id")
    Optional<CampaignActivity> findWithCampaignById(@Param("id") Long id);

    @EntityGraph(attributePaths = {"product", "coupon"})
    @Query("SELECT ca FROM CampaignActivity ca WHERE ca.id = :id")
    Optional<CampaignActivity> findWithProductAndCouponById(@Param("id") Long id);

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
