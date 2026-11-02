package com.axon.core_service.service.purchase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;

import com.axon.core_service.AbstractIntegrationTest;
import com.axon.core_service.domain.purchase.Purchase;
import com.axon.core_service.domain.purchase.PurchaseType;
import com.axon.core_service.domain.user.Role;
import com.axon.core_service.domain.user.User;
import com.axon.core_service.repository.PurchaseRepository;
import com.axon.core_service.repository.UserRepository;
import com.axon.core_service.repository.UserSummaryRepository;
import com.axon.core_service.service.UserSummaryService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;

class PurchaseCancellationConcurrencyIntegrationTest extends AbstractIntegrationTest {
    @Autowired private UserRepository users;
    @Autowired private PurchaseRepository purchases;
    @Autowired private UserSummaryRepository summaries;
    @Autowired private PurchaseCancellationService cancellations;
    @SpyBean private UserSummaryService summaryService;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentPurchaseIsIncludedWhenRebuildingAfterCancellationOrRefund(boolean refund) throws Exception {
        User user = users.saveAndFlush(User.builder().name("concurrent purchase")
                .email(UUID.randomUUID() + "@example.test").role(Role.USER).build());
        Instant first = Instant.parse("2026-08-01T00:00:00Z");
        Instant latest = Instant.parse("2026-08-02T00:00:00Z");
        Purchase old = purchases.saveAndFlush(new Purchase(user.getId(), 1L, null, PurchaseType.SHOP,
                BigDecimal.TEN, 1, first));
        summaryService.recordPurchase(user.getId(), first);

        CountDownLatch cancellationHasRead = new CountDownLatch(1);
        CountDownLatch purchaseHasCommitted = new CountDownLatch(1);
        doAnswer(invocation -> {
            cancellationHasRead.countDown();
            if (!purchaseHasCommitted.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("new purchase did not commit");
            }
            return invocation.callRealMethod();
        }).when(summaryService).rebuildPurchaseSummary(anyLong());

        try (var executor = Executors.newSingleThreadExecutor()) {
            var cancellation = executor.submit(() -> refund
                    ? cancellations.refundConfirmedPurchase(old.getId(), "test", Instant.now())
                    : cancellations.cancelConfirmedPurchase(old.getId(), "test", Instant.now()));
            try {
                assertThat(cancellationHasRead.await(10, TimeUnit.SECONDS)).isTrue();
                purchases.saveAndFlush(new Purchase(user.getId(), 2L, null, PurchaseType.SHOP,
                        BigDecimal.TEN, 1, latest));
                summaryService.recordPurchase(user.getId(), latest);
            } finally {
                purchaseHasCommitted.countDown();
            }
            assertThat(cancellation.get(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(summaries.findById(user.getId()).orElseThrow().getLastPurchaseAt())
                .isEqualTo(LocalDateTime.of(2026, 8, 2, 9, 0));
    }
}
