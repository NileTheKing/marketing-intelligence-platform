package com.axon.core_service.domain.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class UserSummaryTest {

    @Test
    void advanceLastPurchaseAtDoesNotMoveBackwardsForRedeliveredOldMessage() {
        User user = User.builder().name("user").email("user@example.com").role(Role.USER).build();
        Instant newer = Instant.parse("2026-08-03T03:00:00Z");
        Instant older = Instant.parse("2026-08-02T03:00:00Z");

        user.getUserSummary().advanceLastPurchaseAt(newer);
        user.getUserSummary().advanceLastPurchaseAt(older);

        assertThat(user.getUserSummary().getLastPurchaseAt())
                .isEqualTo(LocalDateTime.ofInstant(newer, ZoneId.of("Asia/Seoul")));
    }
}
