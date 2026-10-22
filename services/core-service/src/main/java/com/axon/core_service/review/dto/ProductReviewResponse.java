package com.axon.core_service.review.dto;

import com.axon.core_service.review.domain.ProductReview;
import java.time.LocalDateTime;

public record ProductReviewResponse(Long id, Integer rating, String content, LocalDateTime createdAt) {
    public static ProductReviewResponse from(ProductReview review) {
        return new ProductReviewResponse(review.getId(), review.getRating(), review.getContent(), review.getCreatedAt());
    }
}
