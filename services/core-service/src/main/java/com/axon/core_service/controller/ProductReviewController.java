package com.axon.core_service.controller;

import com.axon.core_service.domain.user.CustomOAuth2User;
import com.axon.core_service.domain.dto.review.ProductReviewCreateRequest;
import com.axon.core_service.domain.dto.review.ProductReviewResponse;
import com.axon.core_service.domain.dto.review.ProductReviewSliceResponse;
import com.axon.core_service.service.ProductReviewService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ProductReviewController {
    private final ProductReviewService productReviewService;

    @PostMapping("/api/v1/products/{productId}/reviews")
    public ResponseEntity<ProductReviewResponse> createReview(@PathVariable Long productId,
                                                               @Valid @RequestBody ProductReviewCreateRequest request,
                                                               @AuthenticationPrincipal CustomOAuth2User principal) {
        ProductReviewResponse response = productReviewService.create(productId, principal.getUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/api/v1/products/{productId}/reviews")
    public ProductReviewSliceResponse getReviews(@PathVariable Long productId,
                                                  @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "20") int size) {
        return productReviewService.findByProduct(productId, page, size);
    }
}
