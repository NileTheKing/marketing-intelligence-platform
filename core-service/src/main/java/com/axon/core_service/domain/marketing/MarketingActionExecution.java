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
        @Index(name = "idx_marketing_execution_status_dlt", columnList = "status,dlt_at"),
        @Index(name = "idx_marketing_execution_action_target", columnList = "marketing_action_id,user_id,product_id")
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

    @Column(name = "marketing_rule_id", nullable = false)
    private Long ruleId;

    @Column(name = "action_reference_id", nullable = false)
    private Long actionReferenceId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "product_id")
    private Long productId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RewardType channel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MarketingActionExecutionStatus status;

    @Column(nullable = false)
    private int attemptCount;

    @Column(name = "dispatch_version", nullable = false)
    private long dispatchVersion;

    @Column(name = "last_failure_reason", length = 1000)
    private String lastFailureReason;

    @Column(name = "dispatched_at")
    private LocalDateTime dispatchedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "dlt_at")
    private LocalDateTime dltAt;

    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;

    @Builder
    private MarketingActionExecution(Long actionId, Long ruleId, Long actionReferenceId,
                                     Long userId, Long productId, RewardType channel) {
        this.actionId = actionId;
        this.ruleId = ruleId;
        this.actionReferenceId = actionReferenceId;
        this.userId = userId;
        this.productId = productId;
        this.channel = channel;
        this.status = MarketingActionExecutionStatus.PENDING;
        this.attemptCount = 0;
        this.dispatchVersion = 1L;
    }
}
