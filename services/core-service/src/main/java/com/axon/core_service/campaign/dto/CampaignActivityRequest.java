package com.axon.core_service.campaign.dto;

import com.axon.core_service.campaign.dto.filter.FilterDetail;
import com.axon.messaging.CampaignActivityType;
import com.axon.core_service.campaign.domain.CampaignActivityPurpose;
import com.axon.core_service.campaign.domain.CouponDeliveryMode;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CampaignActivityRequest {

    private Long campaignId;

    @NotBlank
    private String name;

    @PositiveOrZero
    private Integer limitCount;

    @NotNull
    private CampaignActivityStatus status;

    @NotNull
    private LocalDateTime startDate;

    @NotNull
    private LocalDateTime endDate;

    @NotNull
    private CampaignActivityType activityType;

    private List<FilterDetail> filters;

    @PositiveOrZero
    private BigDecimal price;

    private Long productId;

    private Long couponId;

    @PositiveOrZero
    private Integer quantity;

    private CouponDeliveryMode couponDeliveryMode;

    private Integer delayMinutes;

    private Integer dormantDays;

    @PositiveOrZero
    private BigDecimal budget;

    @PositiveOrZero
    private Integer expectedRecipientCount;

    @PositiveOrZero
    private Integer maxRecipientCount;

    @Min(1)
    @Max(50)
    private Integer holdoutRatePercent;

    private CampaignActivityPurpose purpose;

    @Size(max = 2000)
    private String operatorMemo;

    private String imageUrl;

    @JsonIgnore
    @AssertTrue(message = "endDate must be after startDate")
    public boolean isDateRangeValid() {
        return startDate == null || endDate == null || endDate.isAfter(startDate);
    }

    @JsonIgnore
    @AssertTrue(message = "quantity must be positive for product activities")
    public boolean isQuantityValid() {
        return activityType == null || activityType == CampaignActivityType.COUPON
                ? quantity == null || quantity >= 0
                : quantity != null && quantity > 0;
    }

    @JsonIgnore
    @AssertTrue(message = "price is required for product activities")
    public boolean isPriceValid() {
        return activityType == null || activityType == CampaignActivityType.COUPON
                || price != null;
    }

}
