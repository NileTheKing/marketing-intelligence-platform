package com.axon.core_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.axon.core_service.campaign.domain.Campaign;
import com.axon.core_service.campaign.domain.CampaignActivity;
import com.axon.core_service.campaign.domain.CampaignActivityRun;
import com.axon.core_service.campaign.domain.CampaignActivityRunStatus;
import com.axon.core_service.campaign.CampaignActivityRepository;
import com.axon.core_service.campaign.CampaignActivityRunRepository;
import com.axon.core_service.campaign.CampaignRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class DashboardPageServiceTest {

    @Test
    void activityPageDataContainsOnlyValuesNeededByTheTemplate() {
        CampaignActivityRepository activityRepository = mock(CampaignActivityRepository.class);
        Campaign campaign = new Campaign("Summer campaign");
        ReflectionTestUtils.setField(campaign, "id", 7L);
        CampaignActivity activity = CampaignActivity.builder()
                .campaign(campaign)
                .name("Summer FCFS")
                .build();
        when(activityRepository.findWithCampaignById(3L)).thenReturn(Optional.of(activity));
        DashboardPageService service = new DashboardPageService(activityRepository, mock(CampaignRepository.class),
                mock(CampaignActivityRunRepository.class));

        DashboardPageService.ActivityDashboardPageData result = service.getActivityDashboardPageData(3L);

        assertThat(result.activityName()).isEqualTo("Summer FCFS");
        assertThat(result.parentCampaignId()).isEqualTo(7L);
        assertThat(result.parentCampaignName()).isEqualTo("Summer campaign");
    }

    @Test
    void missingCampaignUsesTheExistingFallbackName() {
        CampaignRepository campaignRepository = mock(CampaignRepository.class);
        when(campaignRepository.findById(9L)).thenReturn(Optional.empty());
        DashboardPageService service = new DashboardPageService(mock(CampaignActivityRepository.class), campaignRepository,
                mock(CampaignActivityRunRepository.class));

        assertThat(service.getCampaignDashboardName(9L)).isEqualTo("Campaign #9");
    }

    @Test
    void incrementalityPageListsRunsNewestFirstWithCampaignNames() {
        Campaign campaign = new Campaign("Autumn sale");
        CampaignActivity activity = CampaignActivity.builder().campaign(campaign).name("15% coupon").build();
        CampaignActivityRun older = CampaignActivityRun.builder().campaignActivity(activity).holdoutCount(10).build();
        CampaignActivityRun newer = CampaignActivityRun.builder().campaignActivity(activity).holdoutCount(10).build();
        older.markDispatched();
        newer.markDispatched();
        CampaignActivityRun pending = CampaignActivityRun.builder().campaignActivity(activity)
                .status(CampaignActivityRunStatus.AWAITING_APPROVAL).holdoutCount(10).build();
        CampaignActivityRun noHoldout = CampaignActivityRun.builder().campaignActivity(activity).build();
        noHoldout.markDispatched();
        CampaignActivityRun noDispatchDate = CampaignActivityRun.builder().campaignActivity(activity)
                .status(CampaignActivityRunStatus.DISPATCHED).holdoutCount(10).build();
        ReflectionTestUtils.setField(older, "id", 1L);
        ReflectionTestUtils.setField(newer, "id", 2L);
        ReflectionTestUtils.setField(pending, "id", 7L);
        ReflectionTestUtils.setField(noHoldout, "id", 6L);
        ReflectionTestUtils.setField(noDispatchDate, "id", 5L);
        CampaignActivityRunRepository runRepository = mock(CampaignActivityRunRepository.class);
        when(runRepository.findAll()).thenReturn(List.of(pending, older, noHoldout, newer, noDispatchDate));
        when(runRepository.findById(1L)).thenReturn(Optional.of(older));
        DashboardPageService service = new DashboardPageService(mock(CampaignActivityRepository.class),
                mock(CampaignRepository.class), runRepository);

        DashboardPageService.IncrementalityPageData result = service.getIncrementalityPageData(1L);

        assertThat(result.activityName()).isEqualTo("15% coupon");
        assertThat(result.campaignName()).isEqualTo("Autumn sale");
        assertThat(result.analysisAvailable()).isTrue();
        assertThat(result.runs()).extracting(DashboardPageService.RunOption::runId).containsExactly(2L, 1L);
        assertThat(result.runs().get(0).label()).isEqualTo("Autumn sale · 15% coupon");
        assertThat(service.getLatestRunId()).contains(2L);
    }

    @Test
    void pendingRunDirectLinkDoesNotEnableAnalysis() {
        CampaignActivity activity = CampaignActivity.builder().name("Pending coupon").build();
        CampaignActivityRun pending = CampaignActivityRun.builder().campaignActivity(activity)
                .status(CampaignActivityRunStatus.AWAITING_APPROVAL).holdoutCount(10).build();
        CampaignActivityRunRepository runs = mock(CampaignActivityRunRepository.class);
        when(runs.findAll()).thenReturn(List.of(pending));
        when(runs.findById(7L)).thenReturn(Optional.of(pending));
        DashboardPageService service = new DashboardPageService(mock(CampaignActivityRepository.class),
                mock(CampaignRepository.class), runs);

        assertThat(service.getLatestRunId()).isEmpty();
        DashboardPageService.IncrementalityPageData result = service.getIncrementalityPageData(7L);
        assertThat(result.analysisAvailable()).isFalse();
        assertThat(result.runs()).isEmpty();
    }

    @Test
    void noRunsOrMissingRunDoesNotEnableAnalysis() {
        CampaignActivityRunRepository runs = mock(CampaignActivityRunRepository.class);
        DashboardPageService service = new DashboardPageService(mock(CampaignActivityRepository.class),
                mock(CampaignRepository.class), runs);

        assertThat(service.getLatestRunId()).isEmpty();
        assertThat(service.getIncrementalityPageData(99L).analysisAvailable()).isFalse();
    }
}
