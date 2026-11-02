package com.axon.entry_service.reservation.dto;

import com.axon.entry_service.reservation.domain.CampaignActivityStatus;
import com.axon.messaging.CampaignActivityType;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CampaignActivitySummaryResponse(
                Long id,
                Long campaignId,
                Integer limitCount,
                CampaignActivityStatus status,
                LocalDateTime startDate,
                LocalDateTime endDate,
                List<Map<String, Object>> filters,
                Long productId,
                Long couponId,
                CampaignActivityType activityType,
                String couponDeliveryMode,
                BigDecimal paymentUnitPrice,
                String paymentCurrency) {
    public CampaignActivitySummaryResponse(Long id, Long campaignId, Integer limitCount, CampaignActivityStatus status,
            LocalDateTime startDate, LocalDateTime endDate, List<Map<String, Object>> filters, Long productId,
            Long couponId, CampaignActivityType activityType, String couponDeliveryMode) {
        this(id, campaignId, limitCount, status, startDate, endDate, filters, productId, couponId, activityType,
                couponDeliveryMode, null, null);
    }
}
