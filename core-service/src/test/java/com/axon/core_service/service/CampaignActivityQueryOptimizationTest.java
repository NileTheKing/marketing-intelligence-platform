package com.axon.core_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.axon.core_service.domain.campaign.Campaign;
import com.axon.core_service.domain.campaignactivity.CampaignActivity;
import com.axon.core_service.domain.dto.campaignactivityentry.CampaignActivityEntryCount;
import com.axon.core_service.repository.CampaignActivityEntryRepository;
import com.axon.core_service.repository.CampaignActivityRepository;
import com.axon.core_service.repository.CampaignRepository;
import com.axon.core_service.repository.CouponRepository;
import com.axon.core_service.repository.ProductRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

class CampaignActivityQueryOptimizationTest {

    private CampaignActivityRepository activityRepository;
    private CampaignActivityEntryRepository entryRepository;
    private CampaignActivityService service;

    @BeforeEach
    void setUp() {
        activityRepository = mock(CampaignActivityRepository.class);
        entryRepository = mock(CampaignActivityEntryRepository.class);
        service = new CampaignActivityService(
                mock(CampaignRepository.class),
                activityRepository,
                entryRepository,
                mock(ProductRepository.class),
                mock(CouponRepository.class),
                mock(StringRedisTemplate.class));
    }

    @Test
    void campaignActivityListLoadsParticipantCountsInOneGroupedQuery() {
        CampaignActivity first = activity(11L);
        CampaignActivity second = activity(12L);
        when(activityRepository.findAllByCampaign_Id(1L)).thenReturn(List.of(first, second));
        when(entryRepository.countByCampaignActivityIds(List.of(11L, 12L)))
                .thenReturn(List.of(new CampaignActivityEntryCount(11L, 7L)));

        var responses = service.getCampaignActivities(1L);

        assertThat(responses).extracting("participantCount").containsExactly(7L, 0L);
        verify(entryRepository).countByCampaignActivityIds(List.of(11L, 12L));
        verify(entryRepository, never()).countByCampaignActivity_Id(11L);
        verify(entryRepository, never()).countByCampaignActivity_Id(12L);
    }

    @Test
    void allActivityListUsesAssociationFetchQueryAndOneGroupedCountQuery() {
        CampaignActivity activity = activity(21L);
        when(activityRepository.findAllWithProductAndCoupon()).thenReturn(List.of(activity));
        when(entryRepository.countByCampaignActivityIds(List.of(21L)))
                .thenReturn(List.of(new CampaignActivityEntryCount(21L, 3L)));

        var responses = service.getAllCampaignActivities();

        assertThat(responses).extracting("participantCount").containsExactly(3L);
        verify(activityRepository).findAllWithProductAndCoupon();
        verify(activityRepository, never()).findAll();
        verify(entryRepository).countByCampaignActivityIds(List.of(21L));
    }

    private CampaignActivity activity(Long id) {
        Campaign campaign = Campaign.builder().name("campaign").build();
        ReflectionTestUtils.setField(campaign, "id", 1L);
        CampaignActivity activity = CampaignActivity.builder()
                .campaign(campaign)
                .name("activity-" + id)
                .build();
        ReflectionTestUtils.setField(activity, "id", id);
        return activity;
    }
}
