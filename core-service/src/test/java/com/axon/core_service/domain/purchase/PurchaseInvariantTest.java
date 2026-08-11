package com.axon.core_service.domain.purchase;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class PurchaseInvariantTest {

    @Test
    void rejectsNonPositiveQuantity() {
        assertThatThrownBy(() -> purchase(BigDecimal.TEN, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("quantity");
    }

    @Test
    void rejectsNegativePrice() {
        assertThatThrownBy(() -> purchase(BigDecimal.valueOf(-1), 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("price");
    }

    private Purchase purchase(BigDecimal price, int quantity) {
        return new Purchase(1L, 2L, 3L, PurchaseType.CAMPAIGNACTIVITY,
                price, quantity, Instant.parse("2026-08-11T00:00:00Z"));
    }
}
