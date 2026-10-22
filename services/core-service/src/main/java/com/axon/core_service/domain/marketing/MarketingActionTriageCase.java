package com.axon.core_service.domain.marketing;

import com.axon.messaging.MarketingActionFailureCategory;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "marketing_action_triage_cases",
        uniqueConstraints = @UniqueConstraint(name = "uk_marketing_triage_dispatch", columnNames = "dispatch_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class MarketingActionTriageCase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "dispatch_id", nullable = false)
    private MarketingActionDispatch dispatch;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private MarketingActionTriageStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_category", nullable = false, length = 32)
    private MarketingActionFailureCategory failureCategory;

    @Column(name = "analysis_claim_token", length = 80)
    private String analysisClaimToken;

    @Column(name = "analysis_claim_expires_at")
    private LocalDateTime analysisClaimExpiresAt;

    @Column(name = "analysis_attempt_count", nullable = false)
    private int analysisAttemptCount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "fact_snapshot_json", columnDefinition = "json")
    private Map<String, Object> factSnapshot;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private MarketingActionTriageRecommendation recommendation;

    @Column
    private Double confidence;

    @Column(name = "analysis_summary", length = 2000)
    private String analysisSummary;

    @Column(name = "operator_guidance", length = 1000)
    private String operatorGuidance;

    @Column(name = "operator_next_step", length = 1000)
    private String operatorNextStep;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence_json", columnDefinition = "json")
    private List<String> evidence;

    @Column(name = "llm_model", length = 100)
    private String llmModel;

    @Column(name = "failure_reason", length = 1000)
    private String failureReason;

    @Column(name = "slack_message_ts", length = 64)
    private String slackMessageTs;

    @Column(name = "decided_by", length = 128)
    private String decidedBy;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "rejected_reason", length = 1000)
    private String rejectedReason;

    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;

    private MarketingActionTriageCase(MarketingActionDispatch dispatch,
                                      MarketingActionFailureCategory failureCategory,
                                      String failureReason,
                                      String operatorGuidance) {
        this.dispatch = dispatch;
        this.status = MarketingActionTriageStatus.PENDING;
        this.failureCategory = failureCategory == null
                ? MarketingActionFailureCategory.UNKNOWN : failureCategory;
        this.failureReason = failureReason;
        this.operatorGuidance = operatorGuidance;
        this.analysisAttemptCount = 0;
    }

    public static MarketingActionTriageCase pending(MarketingActionDispatch dispatch,
                                                    MarketingActionFailureCategory failureCategory,
                                                    String failureReason,
                                                    String operatorGuidance) {
        return new MarketingActionTriageCase(dispatch, failureCategory, failureReason, operatorGuidance);
    }

    public void claim(String token, LocalDateTime expiresAt) {
        this.status = MarketingActionTriageStatus.ANALYZING;
        this.analysisClaimToken = token;
        this.analysisClaimExpiresAt = expiresAt;
        this.analysisAttemptCount++;
    }

    public boolean hasValidClaim(String token, LocalDateTime now) {
        return status == MarketingActionTriageStatus.ANALYZING
                && analysisClaimToken != null
                && analysisClaimToken.equals(token)
                && analysisClaimExpiresAt != null
                && analysisClaimExpiresAt.isAfter(now);
    }

    public void saveAnalysis(Map<String, Object> factSnapshot,
                             MarketingActionTriageRecommendation recommendation,
                             double confidence,
                             String summary,
                             List<String> evidence,
                             String operatorNextStep,
                             String llmModel) {
        this.factSnapshot = factSnapshot;
        this.recommendation = recommendation;
        this.confidence = confidence;
        this.analysisSummary = summary;
        this.evidence = evidence;
        this.operatorNextStep = operatorNextStep;
        this.llmModel = llmModel;
        this.status = MarketingActionTriageStatus.AWAITING_APPROVAL;
        this.analysisClaimToken = null;
        this.analysisClaimExpiresAt = null;
        this.failureReason = null;
    }

    public void markAnalysisFailed(String reason) {
        this.status = MarketingActionTriageStatus.ANALYSIS_FAILED;
        this.failureReason = reason;
        this.analysisClaimToken = null;
        this.analysisClaimExpiresAt = null;
    }

    public void approve(String decidedBy) {
        this.status = MarketingActionTriageStatus.APPROVED;
        this.decidedBy = decidedBy;
        this.decidedAt = LocalDateTime.now();
        this.rejectedReason = null;
    }

    public void close(String decidedBy, String reason) {
        this.status = MarketingActionTriageStatus.CLOSED;
        this.decidedBy = decidedBy;
        this.decidedAt = LocalDateTime.now();
        this.rejectedReason = reason;
    }

    public void setSlackMessageTs(String slackMessageTs) {
        this.slackMessageTs = slackMessageTs;
    }
}
