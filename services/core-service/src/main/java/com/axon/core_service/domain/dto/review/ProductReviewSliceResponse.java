package com.axon.core_service.domain.dto.review;

import java.util.List;

public record ProductReviewSliceResponse(List<ProductReviewResponse> items, int page, int size, boolean hasNext) {
}
