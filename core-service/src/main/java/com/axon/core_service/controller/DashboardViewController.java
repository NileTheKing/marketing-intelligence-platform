package com.axon.core_service.controller;

import lombok.RequiredArgsConstructor;
import com.axon.core_service.service.DashboardPageService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@Controller
@RequiredArgsConstructor
public class DashboardViewController {

    private final DashboardPageService dashboardPageService;

    @GetMapping("/admin/dashboard/{activityId}")
    public String dashboardView(@PathVariable Long activityId, Model model) {
        DashboardPageService.ActivityDashboardPageData pageData =
                dashboardPageService.getActivityDashboardPageData(activityId);
        model.addAttribute("campaignId", activityId);
        model.addAttribute("activityId", activityId);
        model.addAttribute("campaignName", pageData.activityName());
        model.addAttribute("parentCampaignId", pageData.parentCampaignId());
        model.addAttribute("parentCampaignName", pageData.parentCampaignName());
        return "dashboard";
    }

    @GetMapping("/admin/dashboard/cohort/{activityId}")
    public String cohortDashboardView(@PathVariable Long activityId, Model model) {
        DashboardPageService.CohortDashboardPageData pageData =
                dashboardPageService.getCohortDashboardPageData(activityId);
        model.addAttribute("activityId", activityId);
        model.addAttribute("activityName", pageData.activityName());
        model.addAttribute("campaignId", pageData.campaignId());
        return "cohort-dashboard";
    }

    @GetMapping("/admin/dashboard/campaign/{campaignId}")
    public String campaignDashboardView(@PathVariable Long campaignId, Model model) {
        model.addAttribute("campaignId", campaignId);
        model.addAttribute("campaignName", dashboardPageService.getCampaignDashboardName(campaignId));
        return "campaign-dashboard";
    }

    @GetMapping("/admin/dashboard/overview")
    public String globalDashboardView() {
        return "global-dashboard";
    }
}
