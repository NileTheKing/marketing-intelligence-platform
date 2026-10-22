package com.axon.core_service.domain.dto.marketing.triage;

import com.axon.core_service.domain.marketing.MarketingActionTriageCase;

import java.time.LocalDateTime;

public record TriageCaseResponse(
        Long caseId,
        Long dispatchId,
        Long executionId,
        String status,
        String failureCategory,
        String failureReason,
        String analysisClaimToken,
        LocalDateTime analysisClaimExpiresAt,
        int analysisAttemptCount,
        String recommendation,
        Double confidence,
        String analysisSummary,
        String operatorGuidance,
        String operatorNextStep,
        Object factSnapshot,
        Object evidence,
        String llmModel,
        String slackMessageTs,
        String decidedBy,
        LocalDateTime decidedAt,
        String rejectedReason
) {
    public static TriageCaseResponse from(MarketingActionTriageCase triageCase) {
        return new TriageCaseResponse(
                triageCase.getId(),
                triageCase.getDispatch().getId(),
                triageCase.getDispatch().getExecution().getId(),
                triageCase.getStatus().name(),
                triageCase.getFailureCategory().name(),
                triageCase.getFailureReason(),
                triageCase.getAnalysisClaimToken(),
                triageCase.getAnalysisClaimExpiresAt(),
                triageCase.getAnalysisAttemptCount(),
                triageCase.getRecommendation() == null ? null : triageCase.getRecommendation().name(),
                triageCase.getConfidence(),
                triageCase.getAnalysisSummary(),
                triageCase.getOperatorGuidance(),
                triageCase.getOperatorNextStep(),
                triageCase.getFactSnapshot(),
                triageCase.getEvidence(),
                triageCase.getLlmModel(),
                triageCase.getSlackMessageTs(),
                triageCase.getDecidedBy(),
                triageCase.getDecidedAt(),
                triageCase.getRejectedReason());
    }
}
