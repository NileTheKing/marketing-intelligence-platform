package com.axon.core_service.controller;

import com.axon.core_service.domain.dto.marketing.triage.TriageAnalysisRequest;
import com.axon.core_service.domain.dto.marketing.triage.TriageAnalysisFailureRequest;
import com.axon.core_service.domain.dto.marketing.triage.TriageCaseResponse;
import com.axon.core_service.domain.dto.marketing.triage.TriageDecisionRequest;
import com.axon.core_service.domain.dto.marketing.triage.TriageDispatchContextResponse;
import com.axon.core_service.domain.dto.marketing.triage.TriageDispatchResponse;
import com.axon.core_service.domain.dto.marketing.triage.TriageFailureHistoryResponse;
import com.axon.core_service.service.MarketingActionTriageService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/v1/marketing-triage")
public class MarketingActionTriageController {

    private final MarketingActionTriageService triageService;

    @PostMapping("/cases/claim")
    public ResponseEntity<TriageCaseResponse> claim(@RequestParam(required = false) Long caseId) {
        TriageCaseResponse response = triageService.claim(caseId);
        return response == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(response);
    }

    @GetMapping("/dispatches/{dispatchId}/context")
    public TriageDispatchContextResponse dispatchContext(@PathVariable Long dispatchId) {
        return triageService.dispatchContext(dispatchId);
    }

    @GetMapping("/actions/{actionId}/failure-history")
    public TriageFailureHistoryResponse failureHistory(@PathVariable Long actionId,
                                                        @RequestParam(defaultValue = "30") int days) {
        return triageService.failureHistory(actionId, days);
    }

    @GetMapping("/executions/{executionId}/dispatch-history")
    public List<TriageDispatchResponse> dispatchHistory(@PathVariable Long executionId) {
        return triageService.dispatchHistory(executionId);
    }

    @PostMapping("/cases/{caseId}/analysis")
    public TriageCaseResponse saveAnalysis(@PathVariable Long caseId,
                                           @Valid @RequestBody TriageAnalysisRequest request) {
        return triageService.saveAnalysis(caseId, request);
    }

    @PostMapping("/cases/{caseId}/analysis-failed")
    public TriageCaseResponse failAnalysis(@PathVariable Long caseId,
                                           @Valid @RequestBody TriageAnalysisFailureRequest request) {
        return triageService.failAnalysis(caseId, request.claimToken(), request.reason());
    }

    @PostMapping("/cases/{caseId}/decision")
    public TriageCaseResponse decision(@PathVariable Long caseId,
                                       @Valid @RequestBody TriageDecisionRequest request) {
        return triageService.decide(caseId, request);
    }

    @PostMapping("/cases/{caseId}/slack-message")
    public TriageCaseResponse recordSlackMessage(@PathVariable Long caseId,
                                                 @RequestBody SlackMessageRequest request) {
        return triageService.recordSlackMessage(caseId, request.messageTs());
    }

    public record SlackMessageRequest(String messageTs) {
    }
}
