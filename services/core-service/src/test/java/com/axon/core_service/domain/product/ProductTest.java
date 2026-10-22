package com.axon.core_service.domain.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ProductTest {

    @Test
    void negativeDecreaseCannotIncreaseStock() {
        Product product = new Product("product", 10L, BigDecimal.TEN, "category");

        assertThatThrownBy(() -> product.decreaseStock(-1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(product.getStock()).isEqualTo(10L);
    }
}
