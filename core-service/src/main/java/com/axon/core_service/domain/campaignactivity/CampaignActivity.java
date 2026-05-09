package com.axon.core_service.domain.campaignactivity;

import com.axon.core_service.domain.campaign.Campaign;
import com.axon.core_service.domain.common.BaseTimeEntity;
import com.axon.core_service.domain.coupon.Coupon;
import com.axon.core_service.domain.dto.campaignactivity.CampaignActivityStatus;
import com.axon.core_service.domain.dto.campaignactivity.filter.FilterDetail;
import com.axon.core_service.domain.dto.campaignactivity.filter.converter.FilterDetailConverter;
import com.axon.core_service.domain.product.Product;
import com.axon.messaging.CampaignActivityType;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "campaign_activities")
public class CampaignActivity extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "campaign_id")
    private Campaign campaign;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id")
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "coupon_id")
    private Coupon coupon;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "limit_count")
    private Integer limitCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private CampaignActivityStatus status;

    @Column(name = "start_date", nullable = false)
    private LocalDateTime startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDateTime endDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "activity_type", nullable = false)
    private CampaignActivityType activityType;

    @Convert(converter = FilterDetailConverter.class)
    @Column(name = "filters", columnDefinition = "JSON")
    private List<FilterDetail> filters;

    @Column(name = "price", nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    @Column(name = "budget", precision = 12, scale = 2)
    private BigDecimal budget;

    @Column(name = "image_url")
    private String imageUrl;

    @Column(name = "synced_count", nullable = false)
    private Integer syncedCount = 0;

    @Builder
    public CampaignActivity(Campaign campaign,
            Product product,
            String name,
            Integer limitCount,
            CampaignActivityStatus status,
            LocalDateTime startDate,
            LocalDateTime endDate,
            CampaignActivityType activityType,
            List<FilterDetail> filters,
            BigDecimal price,
            Integer quantity,
            BigDecimal budget,
            String imageUrl,
            Coupon coupon,
            Integer syncedCount) {
        this.campaign = campaign;
        this.product = product;
        this.coupon = coupon;
        this.name = name;
        this.limitCount = limitCount;
        this.status = status;
        this.startDate = startDate;
        this.endDate = endDate;
        this.activityType = activityType;
        this.filters = filters;
        this.price = price;
        this.quantity = quantity;
        this.imageUrl = imageUrl;
        this.budget = budget;
        this.syncedCount = syncedCount != null ? syncedCount : 0;
    }

    public Long getCampaignId() {
        return campaign != null ? campaign.getId() : null;
    }

    public Long getProductId() {
        return product != null ? product.getId() : null;
    }

    public void updateInfo(String name, Integer limitCount) {
        this.name = name;
        this.limitCount = limitCount;
    }

    public void changeStatus(CampaignActivityStatus nextStatus) {
        this.status = nextStatus;
    }

    public void changeDates(LocalDateTime startDate, LocalDateTime endDate) {
        this.startDate = startDate;
        this.endDate = endDate;
    }

    public void setFilters(List<FilterDetail> filters) {
        this.filters = filters;
    }

    public void updateProductInfo(Product product, BigDecimal price, Integer quantity) {
        this.product = product;
        this.coupon = null;
        this.price = price;
        this.quantity = quantity;
    }

    public void updateCouponInfo(Coupon coupon) {
        this.coupon = coupon;
        this.product = null;
        this.price = BigDecimal.ZERO;
        this.quantity = 0;
    }

    public void updateActivityType(CampaignActivityType activityType) {
        this.activityType = activityType;
    }

    public void updateImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }

    public void updateBudget(BigDecimal budget) {
        this.budget = budget;
    }

    public void updateStatus(CampaignActivityStatus status) {
        this.status = status;
    }

    public void assignCampaign(Campaign campaign) {
        this.campaign = campaign;
    }

    public void updateSyncedCount(Integer count) {
        this.syncedCount = count;
    }
}
