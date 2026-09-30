package com.axon.core_service.domain.marketing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.EntityListeners;
import java.time.LocalDateTime;

@Entity
@Table(name = "marketing_action_executions", indexes = {
        @Index(name = "idx_marketing_execution_action_target", columnList = "marketing_action_id,user_id,product_id")
}, uniqueConstraints = {
        @UniqueConstraint(name = "uk_marketing_execution_campaign_run_target",
                columnNames = {"campaign_activity_run_id", "user_id"})
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class MarketingActionExecution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "marketing_action_id", nullable = false)
    private Long actionId;

    @Column(name = "marketing_rule_id")
    private Long ruleId;

    @Column(name = "action_reference_id", nullable = false)
    private Long actionReferenceId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "product_id")
    private Long productId;

    @Column(name = "campaign_activity_run_id")
    private Long campaignActivityRunId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RewardType channel;

    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;

    @Builder
    private MarketingActionExecution(Long actionId, Long ruleId, Long actionReferenceId,
                                     Long userId, Long productId, Long campaignActivityRunId,
                                     RewardType channel) {
        this.actionId = actionId;
        this.ruleId = ruleId;
        this.actionReferenceId = actionReferenceId;
        this.userId = userId;
        this.productId = productId;
        this.campaignActivityRunId = campaignActivityRunId;
        this.channel = channel;
    }
}
