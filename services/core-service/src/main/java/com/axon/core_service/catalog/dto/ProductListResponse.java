package com.axon.core_service.catalog.dto;

import java.util.List;

public record ProductListResponse(List<ProductListItemDto> items, int page, int size, boolean hasNext) {
}
