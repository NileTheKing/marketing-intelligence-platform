package com.axon.core_service.domain.dto.campaignactivity;

import com.axon.core_service.domain.campaignactivity.CampaignActivityRun;
import java.time.LocalDateTime;

public record CampaignActivityRunResponse(
        Long runId,
        Long campaignActivityId,
        String status,
        int targetCount,
        String blockedReason,
        String recommendation,
        String analysisSummary,
        String slackMessageTs,
        LocalDateTime createdAt,
        LocalDateTime decidedAt
) {
    public static CampaignActivityRunResponse from(CampaignActivityRun run) {
        return new CampaignActivityRunResponse(
                run.getId(),
                run.getCampaignActivity().getId(),
                run.getStatus().name(),
                run.getTargetCount(),
                run.getBlockedReason(),
                run.getRecommendation(),
                run.getAnalysisSummary(),
                run.getSlackMessageTs(),
                run.getCreatedAt(),
                run.getDecidedAt());
    }
}
