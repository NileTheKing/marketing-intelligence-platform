package com.axon.core_service.repository;

import com.axon.core_service.domain.review.ProductReview;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductReviewRepository extends JpaRepository<ProductReview, Long> {
    boolean existsByPurchaseId(Long purchaseId);

    Slice<ProductReview> findByProductId(Long productId, Pageable pageable);
}
