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
@Table(
        name = "marketing_actions",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_marketing_action_rule_type_reference",
                columnNames = {"marketing_rule_id", "action_type", "reference_id"}
        )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class MarketingAction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "marketing_rule_id", nullable = false)
    private MarketingRule marketingRule;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false, length = 20)
    private RewardType actionType;

    @Column(name = "reference_id", nullable = false)
    private Long referenceId; // Coupon ID or Webhook Template ID

    @Column(nullable = false)
    private boolean isActive;

    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;

    @Builder
    public MarketingAction(MarketingRule marketingRule, RewardType actionType, Long referenceId, boolean isActive) {
        this.marketingRule = marketingRule;
        this.actionType = actionType;
        this.referenceId = referenceId;
        this.isActive = isActive;
    }
}
