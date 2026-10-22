package com.axon.core_service.domain.reconciliation;

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
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Getter
@Table(name = "reconciliation_issues",
        indexes = {
                @Index(name = "idx_reconciliation_issue_status_detected", columnList = "status,last_detected_at")
        },
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_reconciliation_issue_fingerprint", columnNames = "fingerprint")
        })
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReconciliationIssue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "issue_type", nullable = false, length = 50)
    private ReconciliationIssueType issueType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReconciliationIssueStatus status;

    @Column(nullable = false, length = 180)
    private String fingerprint;

    @Column(name = "campaign_activity_id")
    private Long campaignActivityId;

    @Column(name = "purchase_id")
    private Long purchaseId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "expected_value")
    private Long expectedValue;

    @Column(name = "observed_value")
    private Long observedValue;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String evidence;

    @Column(name = "first_detected_at", nullable = false)
    private LocalDateTime firstDetectedAt;

    @Column(name = "last_detected_at", nullable = false)
    private LocalDateTime lastDetectedAt;

    @Column(name = "acknowledged_at")
    private LocalDateTime acknowledgedAt;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "resolution_note", length = 500)
    private String resolutionNote;

    private ReconciliationIssue(ReconciliationIssueType issueType, String fingerprint,
                                Long campaignActivityId, Long purchaseId, Long userId,
                                Long expectedValue, Long observedValue, String evidence,
                                LocalDateTime detectedAt) {
        this.issueType = issueType;
        this.status = ReconciliationIssueStatus.OPEN;
        this.fingerprint = fingerprint;
        this.campaignActivityId = campaignActivityId;
        this.purchaseId = purchaseId;
        this.userId = userId;
        this.expectedValue = expectedValue;
        this.observedValue = observedValue;
        this.evidence = evidence;
        this.firstDetectedAt = detectedAt;
        this.lastDetectedAt = detectedAt;
    }

    public static ReconciliationIssue open(ReconciliationIssueType issueType, String fingerprint,
                                           Long campaignActivityId, Long purchaseId, Long userId,
                                           Long expectedValue, Long observedValue, String evidence,
                                           LocalDateTime detectedAt) {
        return new ReconciliationIssue(issueType, fingerprint, campaignActivityId, purchaseId, userId,
                expectedValue, observedValue, evidence, detectedAt);
    }

    public boolean refreshDetection(Long expectedValue, Long observedValue, String evidence,
                                    LocalDateTime detectedAt) {
        this.expectedValue = expectedValue;
        this.observedValue = observedValue;
        this.evidence = evidence;
        this.lastDetectedAt = detectedAt;

        if (status != ReconciliationIssueStatus.RESOLVED) {
            return false;
        }

        status = ReconciliationIssueStatus.OPEN;
        acknowledgedAt = null;
        resolvedAt = null;
        resolutionNote = null;
        return true;
    }

    public void acknowledge(String note, LocalDateTime acknowledgedAt) {
        if (status != ReconciliationIssueStatus.OPEN) {
            throw new IllegalStateException("Only open reconciliation issues can be acknowledged");
        }
        status = ReconciliationIssueStatus.ACKNOWLEDGED;
        this.acknowledgedAt = acknowledgedAt;
        resolutionNote = note;
    }

    public void resolve(String note, LocalDateTime resolvedAt) {
        if (status == ReconciliationIssueStatus.RESOLVED) {
            throw new IllegalStateException("Reconciliation issue is already resolved");
        }
        status = ReconciliationIssueStatus.RESOLVED;
        this.resolvedAt = resolvedAt;
        resolutionNote = note;
    }
}
