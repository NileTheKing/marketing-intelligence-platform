package com.axon.core_service.domain.marketing;

import com.axon.core_service.campaign.domain.CampaignActivity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.OneToOne;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "abandoned_cart_coupon_campaigns", uniqueConstraints = {
        @UniqueConstraint(name = "uk_abandoned_cart_coupon_campaign_action", columnNames = "marketing_action_id")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AbandonedCartCouponCampaign {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "campaign_activity_id", unique = true)
    private CampaignActivity campaignActivity;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "marketing_action_id", nullable = false)
    private MarketingAction marketingAction;

    @Column(name = "delay_minutes", nullable = false)
    private int delayMinutes;

    @Column(nullable = false)
    private boolean isActive;

    @Builder
    public AbandonedCartCouponCampaign(MarketingAction marketingAction, int delayMinutes, boolean isActive,
                                      CampaignActivity campaignActivity) {
        if (marketingAction == null || marketingAction.getActionType() != RewardType.COUPON) {
            throw new IllegalArgumentException("Abandoned-cart campaign requires a coupon action");
        }
        if (delayMinutes <= 0) {
            throw new IllegalArgumentException("delayMinutes must be positive");
        }
        this.marketingAction = marketingAction;
        this.delayMinutes = delayMinutes;
        this.isActive = isActive;
        this.campaignActivity = campaignActivity;
    }

    public void updateDelayMinutes(int delayMinutes) {
        if (delayMinutes <= 0) {
            throw new IllegalArgumentException("delayMinutes must be positive");
        }
        this.delayMinutes = delayMinutes;
    }
}
