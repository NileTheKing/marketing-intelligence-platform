package com.axon.core_service.domain.marketing;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.Map;

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

    @Column(nullable = false, columnDefinition = "int default 30")
    private int dedupTtlDays = 30; // how long the same rule/user/product trigger is suppressed

    @Column(nullable = false)
    private boolean isActive;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "json")
    private Map<String, Object> propertyConditions; // e.g. {"depth": 75} for SCROLL, {"durationSec": 30} for STAY

    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;

    @Builder
    public MarketingRule(String ruleName, String behaviorType, Long targetProductId,
                         int thresholdCount, int lookbackDays, Integer dedupTtlDays,
                         boolean isActive, Map<String, Object> propertyConditions) {
        this.ruleName = ruleName;
        this.behaviorType = behaviorType;
        this.targetProductId = targetProductId;
        this.thresholdCount = thresholdCount;
        this.lookbackDays = lookbackDays;
        this.dedupTtlDays = dedupTtlDays != null && dedupTtlDays > 0 ? dedupTtlDays : 30;
        this.isActive = isActive;
        this.propertyConditions = propertyConditions;
    }
}
