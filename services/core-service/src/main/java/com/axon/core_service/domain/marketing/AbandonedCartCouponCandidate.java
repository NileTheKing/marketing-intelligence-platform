package com.axon.core_service.domain.marketing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "abandoned_cart_coupon_candidates",
        uniqueConstraints = @UniqueConstraint(name = "uk_abandoned_cart_coupon_candidate",
                columnNames = {"campaign_id", "user_id", "product_id"}),
        indexes = @Index(name = "idx_abandoned_cart_candidate_campaign_due",
                columnList = "campaign_id,status,due_at,id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AbandonedCartCouponCandidate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "campaign_id", nullable = false)
    private AbandonedCartCouponCampaign campaign;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "last_cart_activity_at", nullable = false)
    private LocalDateTime lastCartActivityAt;

    @Column(name = "due_at", nullable = false)
    private LocalDateTime dueAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AbandonedCartCouponCandidateStatus status;

    @Column(name = "claimed_at")
    private LocalDateTime claimedAt;

    @Column(name = "marketing_action_execution_id")
    private Long marketingActionExecutionId;

    @Builder
    public AbandonedCartCouponCandidate(AbandonedCartCouponCampaign campaign, Long userId, Long productId,
                                        LocalDateTime lastCartActivityAt, LocalDateTime dueAt) {
        this.campaign = campaign;
        this.userId = userId;
        this.productId = productId;
        this.lastCartActivityAt = lastCartActivityAt;
        this.dueAt = dueAt;
        this.status = AbandonedCartCouponCandidateStatus.SCHEDULED;
    }

    public void reschedule(LocalDateTime cartActivityAt, LocalDateTime nextDueAt) {
        if (status == AbandonedCartCouponCandidateStatus.DISPATCHED
                || status == AbandonedCartCouponCandidateStatus.PROCESSING) {
            return;
        }
        lastCartActivityAt = cartActivityAt;
        dueAt = nextDueAt;
        status = AbandonedCartCouponCandidateStatus.SCHEDULED;
        claimedAt = null;
    }
}
