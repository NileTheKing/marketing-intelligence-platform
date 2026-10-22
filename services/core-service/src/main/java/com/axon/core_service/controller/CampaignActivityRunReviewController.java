package com.axon.core_service.controller;

import com.axon.core_service.domain.dto.campaignactivity.CampaignActivityRunAnalysisFailureRequest;
import com.axon.core_service.domain.dto.campaignactivity.CampaignActivityRunAnalysisRequest;
import com.axon.core_service.domain.dto.campaignactivity.CampaignActivityRunClaimResponse;
import com.axon.core_service.domain.dto.campaignactivity.CampaignActivityRunDecisionRequest;
import com.axon.core_service.domain.dto.campaignactivity.CampaignActivityRunResponse;
import com.axon.core_service.service.CampaignActivityRunService;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/v1/campaign-run-reviews")
public class CampaignActivityRunReviewController {

    private final CampaignActivityRunService runService;

    @PostMapping("/claim")
    public ResponseEntity<CampaignActivityRunClaimResponse> claim(@RequestParam(required = false) Long runId) {
        CampaignActivityRunClaimResponse response = runService.claim(runId);
        return response == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(response);
    }

    @GetMapping("/runs/{runId}/context")
    public Map<String, Object> context(@PathVariable Long runId) {
        return runService.context(runId);
    }

    @PostMapping("/runs/{runId}/analysis")
    public CampaignActivityRunResponse saveAnalysis(@PathVariable Long runId,
                                                    @Valid @RequestBody CampaignActivityRunAnalysisRequest request) {
        return runService.saveAnalysis(runId, request);
    }

    @PostMapping("/runs/{runId}/analysis-failed")
    public CampaignActivityRunResponse failAnalysis(@PathVariable Long runId,
                                                    @Valid @RequestBody CampaignActivityRunAnalysisFailureRequest request) {
        return runService.failAnalysis(runId, request.claimToken(), request.reason());
    }

    @PostMapping("/runs/{runId}/feedback")
    public CampaignActivityRunResponse feedback(@PathVariable Long runId, @RequestBody FeedbackRequest request) {
        return runService.addFeedback(runId, request.feedback());
    }

    @PostMapping("/runs/{runId}/decision")
    public CampaignActivityRunResponse decide(@PathVariable Long runId,
                                              @Valid @RequestBody CampaignActivityRunDecisionRequest request) {
        return runService.decide(runId, request);
    }

    @PostMapping("/runs/{runId}/auto-dispatch")
    public CampaignActivityRunResponse autoDispatch(@PathVariable Long runId) {
        return runService.autoDispatch(runId);
    }

    @PostMapping("/runs/{runId}/slack-message")
    public CampaignActivityRunResponse recordSlackMessage(@PathVariable Long runId,
                                                          @RequestBody SlackMessageRequest request) {
        return runService.recordSlackMessage(runId, request.messageTs());
    }

    public record FeedbackRequest(String feedback) {
    }

    public record SlackMessageRequest(String messageTs) {
    }
}
