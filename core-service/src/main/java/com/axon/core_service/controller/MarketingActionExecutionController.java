package com.axon.core_service.controller;

import com.axon.core_service.domain.dto.marketing.MarketingActionExecutionResponse;
import com.axon.core_service.service.MarketingActionExecutionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/core/api/v1/marketing-action-executions")
public class MarketingActionExecutionController {

    private final MarketingActionExecutionService executionService;

    @GetMapping("/failed")
    public ResponseEntity<List<MarketingActionExecutionResponse>> getFinalFailures() {
        return ResponseEntity.ok(executionService.findFinalFailures().stream()
                .map(MarketingActionExecutionResponse::from)
                .toList());
    }

    @PostMapping("/{executionId}/retry")
    public ResponseEntity<MarketingActionExecutionResponse> retry(@PathVariable Long executionId) {
        return ResponseEntity.ok(MarketingActionExecutionResponse.from(
                executionService.retryApproved(executionId)));
    }
}
