package com.axon.core_service.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.axon.core_service.domain.campaign.Campaign;
import com.axon.core_service.domain.campaignactivity.CampaignActivity;
import com.axon.core_service.domain.campaignactivityentry.CampaignActivityEntry;
import com.axon.core_service.domain.dto.campaignactivity.CampaignActivityStatus;
import com.axon.core_service.domain.dto.campaignactivityentry.CampaignEntryHistoryItem;
import com.axon.messaging.CampaignActivityType;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

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

    @Test
    void customerHistoryUsesStableNewestFirstSliceAndExcludesOtherUsers() {
        Campaign campaign = campaignRepository.save(Campaign.builder().name("history campaign").build());
        CampaignActivity first = activityRepository.save(activity(campaign, "first"));
        CampaignActivity second = activityRepository.save(activity(campaign, "second"));
        CampaignActivity third = activityRepository.save(activity(campaign, "third"));
        Instant tied = Instant.parse("2026-10-19T00:00:00Z");
        entryRepository.saveAll(List.of(
                CampaignActivityEntry.create(first, 101L, 11L, tied),
                CampaignActivityEntry.create(second, 101L, 12L, tied),
                CampaignActivityEntry.create(third, 101L, 13L, tied.minusSeconds(60)),
                CampaignActivityEntry.create(first, 202L, 21L, tied.plusSeconds(86400))));
        entityManager.flush();
        entityManager.clear();

        var sort = Sort.by(Sort.Order.desc("requestedAt"), Sort.Order.desc("id"));
        var firstSlice = entryRepository.findHistoryByUserId(101L, PageRequest.of(0, 2, sort));
        var secondSlice = entryRepository.findHistoryByUserId(101L, PageRequest.of(1, 2, sort));

        assertThat(firstSlice.getContent()).extracting(CampaignEntryHistoryItem::getProductId)
                .containsExactly(12L, 11L);
        assertThat(firstSlice.getContent()).extracting(CampaignEntryHistoryItem::getActivityName)
                .containsExactly("second", "first");
        assertThat(firstSlice.hasNext()).isTrue();
        assertThat(secondSlice.getContent()).extracting(CampaignEntryHistoryItem::getProductId)
                .containsExactly(13L);
        assertThat(secondSlice.hasNext()).isFalse();
    }

    private CampaignActivity activity(Campaign campaign) {
        return activity(campaign, "activity");
    }

    private CampaignActivity activity(Campaign campaign, String name) {
        LocalDateTime now = LocalDateTime.of(2026, 8, 19, 9, 0);
        return CampaignActivity.builder()
                .campaign(campaign)
                .name(name)
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
