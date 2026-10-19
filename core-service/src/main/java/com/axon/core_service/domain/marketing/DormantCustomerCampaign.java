package com.axon.core_service.domain.marketing;

import com.axon.core_service.domain.campaignactivity.CampaignActivity;
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
@Table(name = "dormant_customer_campaigns", uniqueConstraints = {
        @UniqueConstraint(name = "uk_dormant_campaign_action", columnNames = "marketing_action_id")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DormantCustomerCampaign {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "campaign_activity_id", unique = true)
    private CampaignActivity campaignActivity;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "marketing_action_id", nullable = false)
    private MarketingAction marketingAction;

    @Column(name = "dormant_days", nullable = false)
    private int dormantDays;

    @Column(nullable = false)
    private boolean isActive;

    @Builder
    public DormantCustomerCampaign(MarketingAction marketingAction, int dormantDays, boolean isActive,
                                  CampaignActivity campaignActivity) {
        if (dormantDays <= 0) {
            throw new IllegalArgumentException("dormantDays must be positive");
        }
        this.marketingAction = marketingAction;
        this.dormantDays = dormantDays;
        this.isActive = isActive;
        this.campaignActivity = campaignActivity;
    }

    public void updateDormantDays(int dormantDays) {
        if (dormantDays <= 0) {
            throw new IllegalArgumentException("dormantDays must be positive");
        }
        this.dormantDays = dormantDays;
    }
}
