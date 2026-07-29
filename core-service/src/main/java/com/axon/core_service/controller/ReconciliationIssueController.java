package com.axon.core_service.controller;

import com.axon.core_service.domain.dto.reconciliation.ReconciliationIssueResponse;
import com.axon.core_service.domain.dto.reconciliation.ReconciliationIssueStatusRequest;
import com.axon.core_service.service.reconciliation.ReconciliationIssueService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/core/api/v1/reconciliation-issues")
public class ReconciliationIssueController {

    private final ReconciliationIssueService reconciliationIssueService;

    @GetMapping
    public ResponseEntity<List<ReconciliationIssueResponse>> getOpenIssues() {
        List<ReconciliationIssueResponse> issues = reconciliationIssueService.getOpenIssues().stream()
                .map(ReconciliationIssueResponse::from)
                .toList();
        return ResponseEntity.ok(issues);
    }

    @PatchMapping("/{issueId}/acknowledge")
    public ResponseEntity<ReconciliationIssueResponse> acknowledge(@PathVariable Long issueId,
                                                                     @RequestBody ReconciliationIssueStatusRequest request) {
        return ResponseEntity.ok(ReconciliationIssueResponse.from(
                reconciliationIssueService.acknowledge(issueId, request.note())));
    }

    @PatchMapping("/{issueId}/resolve")
    public ResponseEntity<ReconciliationIssueResponse> resolve(@PathVariable Long issueId,
                                                                 @RequestBody ReconciliationIssueStatusRequest request) {
        return ResponseEntity.ok(ReconciliationIssueResponse.from(
                reconciliationIssueService.resolve(issueId, request.note())));
    }
}
