package com.axon.core_service.service;

import com.axon.core_service.campaign.domain.CampaignActivity;
import com.axon.core_service.campaign.domain.CampaignActivityRun;
import com.axon.core_service.campaign.domain.CampaignActivityRunStatus;
import com.axon.core_service.campaign.CampaignActivityRepository;
import com.axon.core_service.campaign.CampaignActivityRunRepository;
import com.axon.core_service.campaign.CampaignRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DashboardPageService {

    private final CampaignActivityRepository campaignActivityRepository;
    private final CampaignRepository campaignRepository;
    private final CampaignActivityRunRepository campaignActivityRunRepository;

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

    @Transactional(readOnly = true)
    public Optional<Long> getLatestRunId() {
        return campaignActivityRunRepository.findAll().stream()
                .filter(this::isAnalyzableRun)
                .map(CampaignActivityRun::getId)
                .max(Comparator.naturalOrder());
    }

    /** The selected run's names plus analyzable runs, newest first, for the run picker. */
    @Transactional(readOnly = true)
    public IncrementalityPageData getIncrementalityPageData(Long runId) {
        List<RunOption> runs = campaignActivityRunRepository.findAll().stream()
                .filter(this::isAnalyzableRun)
                .sorted(Comparator.comparing(CampaignActivityRun::getId).reversed())
                .map(run -> new RunOption(run.getId(), activityLabel(run.getCampaignActivity())))
                .toList();
        return campaignActivityRunRepository.findById(runId)
                .map(run -> new IncrementalityPageData(
                        run.getCampaignActivity().getName(),
                        run.getCampaignActivity().getCampaign() != null
                                ? run.getCampaignActivity().getCampaign().getName() : null,
                        runs, isAnalyzableRun(run)))
                .orElseGet(() -> new IncrementalityPageData("Run #" + runId, null, runs, false));
    }

    private boolean isAnalyzableRun(CampaignActivityRun run) {
        return run.getStatus() == CampaignActivityRunStatus.DISPATCHED
                && run.getDispatchedAt() != null && run.getHoldoutCount() > 0;
    }

    private static String activityLabel(CampaignActivity activity) {
        return activity.getCampaign() != null
                ? activity.getCampaign().getName() + " · " + activity.getName()
                : activity.getName();
    }

    public record ActivityDashboardPageData(String activityName, Long parentCampaignId, String parentCampaignName) {
    }

    public record CohortDashboardPageData(String activityName, Long campaignId) {
    }

    public record IncrementalityPageData(String activityName, String campaignName, List<RunOption> runs,
                                         boolean analysisAvailable) {
    }

    public record RunOption(Long runId, String label) {
    }
}
