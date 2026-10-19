package com.axon.core_service.domain.dto.campaignactivityentry;

import com.axon.core_service.domain.campaignactivityentry.CampaignActivityEntryStatus;
import com.axon.messaging.CampaignActivityType;
import java.time.LocalDateTime;
import lombok.Getter;

@Getter
public class CampaignEntryHistoryItem {
    private final Long id;
    private final Long campaignActivityId;
    private final String activityName;
    private final CampaignActivityType activityType;
    private final CampaignActivityEntryStatus status;
    private final LocalDateTime requestedAt;
    private final LocalDateTime processedAt;
    private final Long productId;

    public CampaignEntryHistoryItem(Long id, Long campaignActivityId, String activityName,
                                    CampaignActivityType activityType, CampaignActivityEntryStatus status,
                                    LocalDateTime requestedAt, LocalDateTime processedAt, Long productId) {
        this.id = id;
        this.campaignActivityId = campaignActivityId;
        this.activityName = activityName;
        this.activityType = activityType;
        this.status = status;
        this.requestedAt = requestedAt;
        this.processedAt = processedAt;
        this.productId = productId;
    }
}
