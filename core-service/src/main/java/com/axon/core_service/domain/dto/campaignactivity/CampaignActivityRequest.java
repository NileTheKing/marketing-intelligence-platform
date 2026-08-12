package com.axon.core_service.domain.dto.campaignactivity;

import com.axon.core_service.domain.dto.campaignactivity.filter.FilterDetail;
import com.axon.messaging.CampaignActivityType;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

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

    @NotNull
    @PositiveOrZero
    private BigDecimal price;

    private Long productId;

    private Long couponId;

    @NotNull
    @PositiveOrZero
    private Integer quantity;

    @PositiveOrZero
    private BigDecimal budget;

    private String imageUrl;

    @JsonIgnore
    @AssertTrue(message = "endDate must be after startDate")
    public boolean isDateRangeValid() {
        return startDate == null || endDate == null || endDate.isAfter(startDate);
    }
}
