package com.axon.core_service.domain.dto.campaignactivity;

import com.axon.core_service.domain.campaignactivity.CampaignActivityRun;

public record CampaignActivityRunClaimResponse(
        Long runId,
        Long campaignActivityId,
        String status,
        int targetCount,
        String analysisClaimToken,
        String slackMessageTs
) {
    public static CampaignActivityRunClaimResponse from(CampaignActivityRun run) {
        return new CampaignActivityRunClaimResponse(
                run.getId(),
                run.getCampaignActivity().getId(),
                run.getStatus().name(),
                run.getTargetCount(),
                run.getAnalysisClaimToken(),
                run.getSlackMessageTs());
    }
}
