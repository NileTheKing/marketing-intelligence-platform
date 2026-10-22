package com.axon.core_service.domain.dto.product;

import java.util.List;

public record ProductListResponse(List<ProductListItemDto> items, int page, int size, boolean hasNext) {
}
