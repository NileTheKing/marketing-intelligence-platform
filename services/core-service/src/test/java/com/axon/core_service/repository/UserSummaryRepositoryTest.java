package com.axon.core_service.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.axon.core_service.domain.user.Role;
import com.axon.core_service.domain.user.User;
import java.time.LocalDateTime;
import org.springframework.data.domain.PageRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@ActiveProfiles("test")
class UserSummaryRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserSummaryRepository userSummaryRepository;

    @Test
    void lateOlderPurchaseCannotMoveLastPurchaseAtBackwards() {
        User user = userRepository.saveAndFlush(User.builder()
                .name("summary-user")
                .email("summary@example.com")
                .role(Role.USER)
                .build());
        LocalDateTime newer = LocalDateTime.of(2026, 8, 3, 12, 0);
        LocalDateTime older = LocalDateTime.of(2026, 8, 2, 12, 0);

        assertThat(userSummaryRepository.advanceLastPurchaseAt(user.getId(), newer)).isOne();
        assertThat(userSummaryRepository.advanceLastPurchaseAt(user.getId(), older)).isZero();

        assertThat(userSummaryRepository.findById(user.getId()))
                .get()
                .extracting(summary -> summary.getLastPurchaseAt())
                .isEqualTo(newer);
    }

    @Test
    void readsTheNextSummaryPageByUserIdWithoutOffsetOrCount() {
        User first = userRepository.save(User.builder()
                .name("first")
                .email("first-keyset@example.com")
                .role(Role.USER)
                .build());
        User second = userRepository.saveAndFlush(User.builder()
                .name("second")
                .email("second-keyset@example.com")
                .role(Role.USER)
                .build());

        var summaries = userSummaryRepository.findByUserIdGreaterThanOrderByUserIdAsc(
                first.getId(), PageRequest.of(0, 1));

        assertThat(summaries).extracting("userId").containsExactly(second.getId());
    }
}
