package com.axon.core_service.controller;

import com.axon.core_service.domain.dto.campaignactivity.CampaignActivityRunResponse;
import com.axon.core_service.service.CampaignActivityRunService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/campaign-activities")
public class CampaignActivityRunController {

    private final CampaignActivityRunService runService;

    @PostMapping("/{activityId}/runs")
    public ResponseEntity<CampaignActivityRunResponse> prepare(@PathVariable Long activityId) {
        return ResponseEntity.ok(runService.prepare(activityId));
    }
}
