package com.axon.core_service.repository;

import com.axon.core_service.domain.marketing.AbandonedCartCouponCandidate;
import com.axon.core_service.domain.marketing.AbandonedCartCouponCandidateStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.axon.core_service.campaign.dto.CampaignActivityStatus;
import com.axon.core_service.campaign.domain.CouponDeliveryMode;
import org.springframework.transaction.annotation.Transactional;

public interface AbandonedCartCouponCandidateRepository extends JpaRepository<AbandonedCartCouponCandidate, Long> {

    Optional<AbandonedCartCouponCandidate> findByCampaign_IdAndUserIdAndProductId(
            Long campaignId, Long userId, Long productId);

    @EntityGraph(attributePaths = {"campaign", "campaign.marketingAction", "campaign.campaignActivity",
            "campaign.campaignActivity.campaign"})
    @Query("select c from AbandonedCartCouponCandidate c where c.status = :status and c.dueAt <= :dueAt "
            + "and c.campaign.campaignActivity.status = :active "
            + "and c.campaign.campaignActivity.couponDeliveryMode = :mode "
            + "and c.campaign.campaignActivity.startDate <= :dueAt and c.campaign.campaignActivity.endDate >= :dueAt "
            + "and (c.campaign.campaignActivity.campaign.startAt is null or c.campaign.campaignActivity.campaign.startAt <= :dueAt) "
            + "and (c.campaign.campaignActivity.campaign.endAt is null or c.campaign.campaignActivity.campaign.endAt >= :dueAt) "
            + "order by c.dueAt asc, c.id asc")
    List<AbandonedCartCouponCandidate> findExecutableDueCandidates(
            @Param("status") AbandonedCartCouponCandidateStatus status,
            @Param("dueAt") LocalDateTime dueAt,
            @Param("active") CampaignActivityStatus active,
            @Param("mode") CouponDeliveryMode mode,
            Pageable pageable);

    // Retained for query-shape benchmarks and old test fixtures; runtime dispatch uses the lifecycle-filtered query above.
    @EntityGraph(attributePaths = {"campaign", "campaign.marketingAction"})
    List<AbandonedCartCouponCandidate> findByStatusAndDueAtLessThanEqualOrderByDueAtAscIdAsc(
            AbandonedCartCouponCandidateStatus status, LocalDateTime dueAt, Pageable pageable);

    @EntityGraph(attributePaths = {"campaign", "campaign.marketingAction", "campaign.campaignActivity",
            "campaign.campaignActivity.campaign"})
    @Query("select c from AbandonedCartCouponCandidate c where c.id = :id")
    Optional<AbandonedCartCouponCandidate> findByIdWithCampaign(@Param("id") Long id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update AbandonedCartCouponCandidate c set c.status = :processing, c.claimedAt = :claimedAt "
            + "where c.id = :id and c.status = :scheduled and c.dueAt <= :now")
    int claim(@Param("id") Long id,
              @Param("scheduled") AbandonedCartCouponCandidateStatus scheduled,
              @Param("processing") AbandonedCartCouponCandidateStatus processing,
              @Param("now") LocalDateTime now,
              @Param("claimedAt") LocalDateTime claimedAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update AbandonedCartCouponCandidate c set c.status = :cancelled "
            + "where c.userId = :userId and c.productId = :productId and c.status in (:scheduled, :processing)")
    int cancelScheduledByUserIdAndProductId(@Param("userId") Long userId,
                                            @Param("productId") Long productId,
                                            @Param("scheduled") AbandonedCartCouponCandidateStatus scheduled,
                                            @Param("processing") AbandonedCartCouponCandidateStatus processing,
                                            @Param("cancelled") AbandonedCartCouponCandidateStatus cancelled);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update AbandonedCartCouponCandidate c set c.status = :cancelled "
            + "where c.id = :id and c.status = :processing")
    int cancelProcessing(@Param("id") Long id,
                         @Param("processing") AbandonedCartCouponCandidateStatus processing,
                         @Param("cancelled") AbandonedCartCouponCandidateStatus cancelled);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update AbandonedCartCouponCandidate c set c.status = :dispatched, "
            + "c.marketingActionExecutionId = :executionId where c.id = :id and c.status = :processing")
    int markDispatched(@Param("id") Long id,
                       @Param("executionId") Long executionId,
                       @Param("processing") AbandonedCartCouponCandidateStatus processing,
                       @Param("dispatched") AbandonedCartCouponCandidateStatus dispatched);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update AbandonedCartCouponCandidate c set c.status = :scheduled, c.claimedAt = null "
            + "where c.id = :id and c.status = :processing")
    int releaseClaim(@Param("id") Long id,
                     @Param("processing") AbandonedCartCouponCandidateStatus processing,
                     @Param("scheduled") AbandonedCartCouponCandidateStatus scheduled);
}
