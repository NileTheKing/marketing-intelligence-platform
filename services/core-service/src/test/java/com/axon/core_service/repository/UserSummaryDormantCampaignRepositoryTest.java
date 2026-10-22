package com.axon.core_service.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.axon.core_service.domain.marketing.MarketingActionExecution;
import com.axon.core_service.domain.marketing.RewardType;
import com.axon.core_service.domain.user.Role;
import com.axon.core_service.domain.user.User;
import com.axon.messaging.dto.validation.Grade;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;

@DataJpaTest
class UserSummaryDormantCampaignRepositoryTest {

    @Autowired private UserRepository userRepository;
    @Autowired private UserSummaryRepository userSummaryRepository;
    @Autowired private MarketingActionExecutionRepository executionRepository;

    @Test
    void findsOnlyDormantUsersWithoutExistingCampaignExecution() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(90);
        User eligible = user("eligible@example.com");
        User alreadySent = user("already-sent@example.com");
        User recent = user("recent@example.com");
        userSummaryRepository.advanceLastPurchaseAt(eligible.getId(), cutoff.minusDays(1));
        userSummaryRepository.advanceLastPurchaseAt(alreadySent.getId(), cutoff.minusDays(2));
        userSummaryRepository.advanceLastPurchaseAt(recent.getId(), cutoff.plusDays(1));
        executionRepository.saveAndFlush(MarketingActionExecution.builder()
                .actionId(5L).actionReferenceId(99L).userId(alreadySent.getId())
                .reengagementCampaignId(7L).channel(RewardType.WEBHOOK).build());

        List<Long> userIds = userSummaryRepository.findDormantUserIdsWithoutCampaignExecution(
                7L, cutoff, PageRequest.of(0, 100));

        assertThat(userIds).containsExactly(eligible.getId());
    }

    private User user(String email) {
        return userRepository.saveAndFlush(User.builder()
                .name(email).email(email).role(Role.USER).grade(Grade.BRONZE).build());
    }
}
