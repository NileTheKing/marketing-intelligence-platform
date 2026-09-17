package com.axon.core_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.axon.core_service.domain.campaign.Campaign;
import com.axon.core_service.domain.campaignactivity.CampaignActivity;
import com.axon.core_service.repository.CampaignActivityRepository;
import com.axon.core_service.repository.CampaignRepository;
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
        DashboardPageService service = new DashboardPageService(activityRepository, mock(CampaignRepository.class));

        DashboardPageService.ActivityDashboardPageData result = service.getActivityDashboardPageData(3L);

        assertThat(result.activityName()).isEqualTo("Summer FCFS");
        assertThat(result.parentCampaignId()).isEqualTo(7L);
        assertThat(result.parentCampaignName()).isEqualTo("Summer campaign");
    }

    @Test
    void missingCampaignUsesTheExistingFallbackName() {
        CampaignRepository campaignRepository = mock(CampaignRepository.class);
        when(campaignRepository.findById(9L)).thenReturn(Optional.empty());
        DashboardPageService service = new DashboardPageService(mock(CampaignActivityRepository.class), campaignRepository);

        assertThat(service.getCampaignDashboardName(9L)).isEqualTo("Campaign #9");
    }
}
