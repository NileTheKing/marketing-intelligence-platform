package com.axon.core_service.service;

import com.axon.core_service.repository.CampaignActivityRepository;
import com.axon.core_service.repository.CampaignRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DashboardPageService {

    private final CampaignActivityRepository campaignActivityRepository;
    private final CampaignRepository campaignRepository;

    @Transactional(readOnly = true)
    public ActivityDashboardPageData getActivityDashboardPageData(Long activityId) {
        return campaignActivityRepository.findWithCampaignById(activityId)
                .map(activity -> new ActivityDashboardPageData(
                        activity.getName(),
                        activity.getCampaign() != null ? activity.getCampaign().getId() : null,
                        activity.getCampaign() != null ? activity.getCampaign().getName() : null))
                .orElseGet(() -> new ActivityDashboardPageData("Activity #" + activityId, null, null));
    }

    @Transactional(readOnly = true)
    public CohortDashboardPageData getCohortDashboardPageData(Long activityId) {
        return campaignActivityRepository.findWithCampaignById(activityId)
                .map(activity -> new CohortDashboardPageData(
                        activity.getName(),
                        activity.getCampaign() != null ? activity.getCampaign().getId() : null))
                .orElseGet(() -> new CohortDashboardPageData("Activity #" + activityId, null));
    }

    @Transactional(readOnly = true)
    public String getCampaignDashboardName(Long campaignId) {
        return campaignRepository.findById(campaignId)
                .map(campaign -> campaign.getName())
                .orElse("Campaign #" + campaignId);
    }

    public record ActivityDashboardPageData(String activityName, Long parentCampaignId, String parentCampaignName) {
    }

    public record CohortDashboardPageData(String activityName, Long campaignId) {
    }
}
