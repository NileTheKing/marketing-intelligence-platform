package com.axon.core_service.domain.purchase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class PurchaseStatusTest {

    @Test
    void confirmedPurchaseCanBeCancelledOnlyOnce() {
        Purchase purchase = purchase();

        assertThat(purchase.cancel("customer request", Instant.parse("2026-07-28T00:00:00Z"))).isTrue();
        assertThat(purchase.getStatus()).isEqualTo(PurchaseStatus.CANCELLED);
        assertThat(purchase.cancel("duplicate", Instant.now())).isFalse();
    }

    @Test
    void cancelledPurchaseCannotBeRefundedAgain() {
        Purchase purchase = purchase();
        purchase.cancel("customer request", Instant.now());

        assertThatThrownBy(() -> purchase.refund("provider refund", Instant.now()))
                .isInstanceOf(IllegalStateException.class);
    }

    private Purchase purchase() {
        return new Purchase(1L, 2L, 3L, PurchaseType.CAMPAIGNACTIVITY,
                BigDecimal.valueOf(10_000), 1, Instant.now());
    }
}
