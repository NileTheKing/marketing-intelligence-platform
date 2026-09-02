package com.axon.core_service.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.axon.core_service.domain.campaign.Campaign;
import com.axon.core_service.domain.campaignactivity.CampaignActivity;
import com.axon.core_service.domain.campaignactivityentry.CampaignActivityEntry;
import com.axon.core_service.domain.dto.campaignactivity.CampaignActivityStatus;
import com.axon.messaging.CampaignActivityType;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

@DataJpaTest
class CampaignQueryOptimizationRepositoryTest {

    @Autowired
    private CampaignRepository campaignRepository;

    @Autowired
    private CampaignActivityRepository activityRepository;

    @Autowired
    private CampaignActivityEntryRepository entryRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void fetchesCampaignActivitiesAndGroupedParticipantCounts() {
        Campaign campaign = campaignRepository.save(Campaign.builder().name("campaign").build());
        CampaignActivity activity = activityRepository.save(activity(campaign));
        entryRepository.saveAll(List.of(
                CampaignActivityEntry.create(activity, 101L, null, Instant.parse("2026-08-19T00:00:00Z")),
                CampaignActivityEntry.create(activity, 102L, null, Instant.parse("2026-08-19T00:00:01Z"))));
        entityManager.flush();
        entityManager.clear();

        List<Campaign> campaigns = campaignRepository.findAllWithActivities();
        var counts = entryRepository.countByCampaignActivityIds(List.of(activity.getId()));

        assertThat(campaigns).hasSize(1);
        assertThat(campaigns.getFirst().getCampaignActivities()).hasSize(1);
        assertThat(counts).singleElement().satisfies(count -> {
            assertThat(count.campaignActivityId()).isEqualTo(activity.getId());
            assertThat(count.participantCount()).isEqualTo(2L);
        });
    }

    private CampaignActivity activity(Campaign campaign) {
        LocalDateTime now = LocalDateTime.of(2026, 8, 19, 9, 0);
        return CampaignActivity.builder()
                .campaign(campaign)
                .name("activity")
                .limitCount(100)
                .status(CampaignActivityStatus.ACTIVE)
                .startDate(now)
                .endDate(now.plusDays(1))
                .activityType(CampaignActivityType.FIRST_COME_FIRST_SERVE)
                .price(BigDecimal.TEN)
                .quantity(1)
                .build();
    }
}
