package com.axon.core_service.commandprocessing;

import static org.assertj.core.api.Assertions.assertThat;

import com.axon.core_service.AbstractIntegrationTest;
import com.axon.core_service.domain.purchase.Purchase;
import com.axon.core_service.domain.purchase.PurchaseType;
import com.axon.core_service.domain.user.Role;
import com.axon.core_service.domain.user.User;
import com.axon.core_service.repository.PurchaseRepository;
import com.axon.core_service.repository.UserRepository;
import com.axon.core_service.repository.UserSummaryRepository;
import com.axon.messaging.topic.KafkaTopics;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.shaded.org.awaitility.Awaitility;

class UserSummaryRecoveryIntegrationTest extends AbstractIntegrationTest {
    @Autowired UserRepository users;
    @Autowired UserSummaryRepository summaries;
    @Autowired PurchaseRepository purchases;
    @Autowired JdbcTemplate jdbc;
    @Autowired KafkaTemplate<String, Object> kafka;

    @Test
    void missingSummaryIsDetectedAndRebuiltFromLedgerByFailureMessage() {
        User user = users.saveAndFlush(User.builder().name("recovery")
                .email(UUID.randomUUID() + "@example.test").role(Role.USER).build());
        Instant purchaseTime = Instant.parse("2026-08-01T00:00:00Z");
        purchases.saveAndFlush(new Purchase(user.getId(), 1L, null, PurchaseType.SHOP,
                BigDecimal.TEN, 1, purchaseTime));
        jdbc.update("DELETE FROM user_summary WHERE user_id = ?", user.getId());

        assertThat(summaries.findPurchaseSummaryMismatches())
                .anyMatch(mismatch -> mismatch.getUserId().equals(user.getId()));

        kafka.send(KafkaTopics.USER_SUMMARY_PROJECTION_FAILED,
                new UserSummaryProjectionFailedEvent(1,
                        List.of(new UserSummaryProjectionFailedEvent.LedgerKey(1L, user.getId())),
                        Instant.now(), "test", "test")).join();

        Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(summaries.findById(user.getId())
                        .map(summary -> summary.getLastPurchaseAt()))
                        .contains(LocalDateTime.of(2026, 8, 1, 9, 0)));
    }
}
