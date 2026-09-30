package com.axon.core_service.domain.campaignactivity;

import com.axon.core_service.domain.common.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "campaign_activity_runs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CampaignActivityRun extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "campaign_activity_id", nullable = false)
    private CampaignActivity campaignActivity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private CampaignActivityRunStatus status;

    @Column(name = "target_count", nullable = false)
    private int targetCount;

    @Column(name = "blocked_reason", length = 1000)
    private String blockedReason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "fact_snapshot_json", columnDefinition = "json")
    private Map<String, Object> factSnapshot;

    @Column(name = "analysis_claim_token", length = 80)
    private String analysisClaimToken;

    @Column(name = "analysis_claim_expires_at")
    private LocalDateTime analysisClaimExpiresAt;

    @Column(name = "analysis_summary", length = 2000)
    private String analysisSummary;

    @Column(name = "recommendation", length = 32)
    private String recommendation;

    @Column(name = "operator_feedback", length = 1000)
    private String operatorFeedback;

    @Column(name = "slack_message_ts", length = 64)
    private String slackMessageTs;

    @Column(name = "decided_by", length = 128)
    private String decidedBy;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Builder
    private CampaignActivityRun(CampaignActivity campaignActivity,
                                CampaignActivityRunStatus status,
                                int targetCount,
                                String blockedReason,
                                Map<String, Object> factSnapshot) {
        this.campaignActivity = campaignActivity;
        this.status = status;
        this.targetCount = targetCount;
        this.blockedReason = blockedReason;
        this.factSnapshot = factSnapshot;
    }

    public static CampaignActivityRun blocked(CampaignActivity activity, int targetCount,
                                              String reason, Map<String, Object> facts) {
        return builder()
                .campaignActivity(activity)
                .status(CampaignActivityRunStatus.BLOCKED)
                .targetCount(targetCount)
                .blockedReason(reason)
                .factSnapshot(facts)
                .build();
    }

    public static CampaignActivityRun pendingAnalysis(CampaignActivity activity, int targetCount,
                                                       Map<String, Object> facts) {
        return builder()
                .campaignActivity(activity)
                .status(CampaignActivityRunStatus.PENDING_ANALYSIS)
                .targetCount(targetCount)
                .factSnapshot(facts)
                .build();
    }

    public void claimAnalysis(String token, LocalDateTime expiresAt) {
        this.status = CampaignActivityRunStatus.ANALYZING;
        this.analysisClaimToken = token;
        this.analysisClaimExpiresAt = expiresAt;
    }

    public boolean hasValidAnalysisClaim(String token, LocalDateTime now) {
        return status == CampaignActivityRunStatus.ANALYZING
                && analysisClaimToken != null
                && analysisClaimToken.equals(token)
                && analysisClaimExpiresAt != null
                && analysisClaimExpiresAt.isAfter(now);
    }

    public void saveAnalysis(String summary, String recommendation) {
        this.analysisSummary = summary;
        this.recommendation = recommendation;
        this.status = CampaignActivityRunStatus.AWAITING_APPROVAL;
        this.analysisClaimToken = null;
        this.analysisClaimExpiresAt = null;
    }

    public void failAnalysis(String reason) {
        this.blockedReason = reason;
        this.recommendation = "FLAG";
        this.analysisSummary = "AI 분석을 완료하지 못해 자동 발송하지 않았습니다. 설정과 대상 수를 확인한 뒤 발송 여부를 결정해야 합니다.";
        this.status = CampaignActivityRunStatus.AWAITING_APPROVAL;
        this.analysisClaimToken = null;
        this.analysisClaimExpiresAt = null;
    }

    public void hold(String reason) {
        this.status = CampaignActivityRunStatus.AWAITING_APPROVAL;
        this.recommendation = "FLAG";
        this.blockedReason = reason;
    }

    public void addOperatorFeedback(String feedback) {
        this.operatorFeedback = feedback;
    }

    public void markDispatching(String decidedBy) {
        this.status = CampaignActivityRunStatus.DISPATCHING;
        this.decidedBy = decidedBy;
        this.decidedAt = LocalDateTime.now();
    }

    public void markDispatched() {
        this.status = CampaignActivityRunStatus.DISPATCHED;
    }

    public void close(String decidedBy, String reason) {
        this.status = CampaignActivityRunStatus.CLOSED;
        this.decidedBy = decidedBy;
        this.decidedAt = LocalDateTime.now();
        this.blockedReason = reason;
    }

    public void setSlackMessageTs(String slackMessageTs) {
        this.slackMessageTs = slackMessageTs;
    }
}
