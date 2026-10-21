package com.axon.core_service.domain.dto.product;

import java.math.BigDecimal;

public record ProductListItemDto(
        Long id,
        String name,
        String brand,
        String category,
        String imageUrl,
        BigDecimal originalPrice,
        BigDecimal sellingPrice,
        Integer discountRate) {
}
