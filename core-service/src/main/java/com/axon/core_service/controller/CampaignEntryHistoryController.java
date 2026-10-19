package com.axon.core_service.controller;

import com.axon.core_service.domain.dto.campaignactivityentry.CampaignEntryHistoryResponse;
import com.axon.core_service.domain.user.CustomOAuth2User;
import com.axon.core_service.service.CampaignEntryHistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me/campaign-entries")
@RequiredArgsConstructor
public class CampaignEntryHistoryController {
    private final CampaignEntryHistoryService historyService;

    @GetMapping
    public CampaignEntryHistoryResponse getMyEntries(@AuthenticationPrincipal CustomOAuth2User principal,
                                                      @RequestParam(defaultValue = "0") int page,
                                                      @RequestParam(defaultValue = "20") int size) {
        return historyService.findByUserId(principal.getUserId(), page, size);
    }
}
