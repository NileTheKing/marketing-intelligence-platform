package com.axon.core_service.domain.marketing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
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
@Table(name = "marketing_action_dispatches",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_marketing_dispatch_execution_sequence",
                columnNames = {"execution_id", "dispatch_sequence"}),
        indexes = {
                @Index(name = "idx_marketing_dispatch_status_dlt", columnList = "status,dlt_at"),
                @Index(name = "idx_marketing_dispatch_execution_sequence", columnList = "execution_id,dispatch_sequence")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class MarketingActionDispatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "execution_id", nullable = false)
    private MarketingActionExecution execution;

    @Column(name = "dispatch_sequence", nullable = false)
    private long sequence;

    @Enumerated(EnumType.STRING)
    @Column(name = "initiated_by", nullable = false, length = 20)
    private MarketingActionDispatchInitiatedBy initiatedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MarketingActionExecutionStatus status;

    @Column(nullable = false)
    private int attemptCount;

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
    private MarketingActionDispatch(MarketingActionExecution execution, long sequence,
                                    MarketingActionDispatchInitiatedBy initiatedBy) {
        this.execution = execution;
        this.sequence = sequence;
        this.initiatedBy = initiatedBy;
        this.status = MarketingActionExecutionStatus.PENDING;
        this.attemptCount = 0;
    }
}
