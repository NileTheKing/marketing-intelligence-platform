package com.axon.core_service.domain.campaignactivity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

@Entity
@Table(name = "campaign_activity_run_targets",
        uniqueConstraints = @UniqueConstraint(name = "uk_campaign_run_target", columnNames = {"run_id", "user_id"}),
        indexes = @Index(name = "idx_campaign_run_target_execution", columnList = "execution_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CampaignActivityRunTarget {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "execution_id", unique = true)
    private Long executionId;

    @Builder
    private CampaignActivityRunTarget(Long runId, Long userId, Long executionId) {
        this.runId = runId;
        this.userId = userId;
        this.executionId = executionId;
    }

    public void assignExecution(Long executionId) {
        this.executionId = executionId;
    }
}
