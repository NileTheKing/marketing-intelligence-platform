package com.axon.core_service.purchase;

import static org.assertj.core.api.Assertions.*;
import com.axon.core_service.catalog.domain.Product;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ShopPriceCalculatorTest {
    @Test void roundsOnlyFinalAmountAndCapsCouponAtSellingPrice() {
        var product = new Product("x", 1L, new BigDecimal("100.01"), "TECH", null, 15);
        var selling = ShopPriceCalculator.sellingPrice(product);
        assertThat(selling).isEqualByComparingTo("85.0085");
        assertThat(ShopPriceCalculator.paidAmount(selling, new BigDecimal("10"))).isEqualByComparingTo("75.01");
        assertThat(ShopPriceCalculator.paidAmount(selling, new BigDecimal("100"))).isEqualByComparingTo("0.00");
    }
    @Test void rejectsInvalidRates() {
        var product = new Product("x", 1L, BigDecimal.TEN, "TECH", null, 101);
        assertThatThrownBy(() -> ShopPriceCalculator.sellingPrice(product)).isInstanceOf(IllegalStateException.class);
    }
}
