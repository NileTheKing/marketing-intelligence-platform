package com.axon.core_service.domain.marketing;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "marketing_rules")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class MarketingRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String ruleName;

    @Column(nullable = false, length = 50)
    private String behaviorType; // e.g. "PAGE_VIEW"

    @Column(name = "target_product_id")
    private Long targetProductId; // null implies any product

    @Column(nullable = false)
    private int thresholdCount; // e.g. 3 views

    @Column(nullable = false)
    private int lookbackDays; // e.g. 7 days

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RewardType rewardType;

    @Column(nullable = false)
    private Long rewardReferenceId; // Coupon ID or Webhook Template ID

    @Column(nullable = false)
    private boolean isActive;

    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;

    @Builder
    public MarketingRule(String ruleName, String behaviorType, Long targetProductId,
                         int thresholdCount, int lookbackDays, RewardType rewardType,
                         Long rewardReferenceId, boolean isActive) {
        this.ruleName = ruleName;
        this.behaviorType = behaviorType;
        this.targetProductId = targetProductId;
        this.thresholdCount = thresholdCount;
        this.lookbackDays = lookbackDays;
        this.rewardType = rewardType;
        this.rewardReferenceId = rewardReferenceId;
        this.isActive = isActive;
    }
}
