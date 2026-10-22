package com.axon.core_service.review.dto;

import java.util.List;

public record ProductReviewSliceResponse(List<ProductReviewResponse> items, int page, int size, boolean hasNext) {
}
