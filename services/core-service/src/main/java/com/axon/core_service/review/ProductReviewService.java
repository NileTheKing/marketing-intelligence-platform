package com.axon.core_service.review;

import com.axon.core_service.review.dto.ProductReviewCreateRequest;
import com.axon.core_service.review.dto.ProductReviewResponse;
import com.axon.core_service.review.dto.ProductReviewSliceResponse;
import com.axon.core_service.domain.purchase.Purchase;
import com.axon.core_service.domain.purchase.PurchaseStatus;
import com.axon.core_service.review.domain.ProductReview;
import com.axon.core_service.exception.BusinessConflictException;
import com.axon.core_service.exception.InvalidRequestException;
import com.axon.core_service.exception.ResourceNotFoundException;
import com.axon.core_service.catalog.ProductRepository;
import com.axon.core_service.repository.PurchaseRepository;
import java.time.LocalDateTime;
import java.util.Locale;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductReviewService {
    private static final String PURCHASE_UNIQUE_CONSTRAINT = "uk_product_review_purchase";

    private final ProductRepository productRepository;
    private final PurchaseRepository purchaseRepository;
    private final ProductReviewRepository productReviewRepository;

    public ProductReviewService(ProductRepository productRepository, PurchaseRepository purchaseRepository,
                                ProductReviewRepository productReviewRepository) {
        this.productRepository = productRepository;
        this.purchaseRepository = purchaseRepository;
        this.productReviewRepository = productReviewRepository;
    }

    @Transactional
    public ProductReviewResponse create(Long productId, Long userId, ProductReviewCreateRequest request) {
        if (!productRepository.existsById(productId)) {
            throw new ResourceNotFoundException("product", productId);
        }
        Purchase purchase = purchaseRepository.findById(request.getPurchaseId())
                .orElseThrow(() -> new ResourceNotFoundException("purchase", request.getPurchaseId()));
        if (!purchase.getUserId().equals(userId)) {
            throw new InvalidRequestException("purchase does not belong to the authenticated user");
        }
        if (!purchase.getProductId().equals(productId)) {
            throw new InvalidRequestException("purchase does not belong to the requested product");
        }
        if (purchase.getStatus() != PurchaseStatus.CONFIRMED) {
            throw new InvalidRequestException("purchase is not confirmed");
        }
        String content = request.getContent().trim();
        if (content.isEmpty() || content.length() > 1000) {
            throw new InvalidRequestException("content must contain 1 to 1000 characters after trimming");
        }
        if (productReviewRepository.existsByPurchaseId(purchase.getId())) {
            throw new BusinessConflictException("purchase already has a review");
        }
        ProductReview review = new ProductReview(productId, purchase.getId(), userId, request.getRating(),
                content, LocalDateTime.now());
        try {
            return ProductReviewResponse.from(productReviewRepository.saveAndFlush(review));
        } catch (DataIntegrityViolationException exception) {
            if (isPurchaseUniqueViolation(exception)) {
                throw new BusinessConflictException("purchase already has a review");
            }
            throw exception;
        }
    }

    @Transactional(readOnly = true)
    public ProductReviewSliceResponse findByProduct(Long productId, int page, int size) {
        if (!productRepository.existsById(productId)) {
            throw new ResourceNotFoundException("product", productId);
        }
        if (page < 0 || size < 1 || size > 100) {
            throw new InvalidRequestException("page must be non-negative and size must be between 1 and 100");
        }
        var slice = productReviewRepository.findByProductId(productId,
                PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))));
        return new ProductReviewSliceResponse(slice.getContent().stream().map(ProductReviewResponse::from).toList(),
                page, size, slice.hasNext());
    }

    private boolean isPurchaseUniqueViolation(DataIntegrityViolationException exception) {
        Throwable cause = exception;
        while (cause != null) {
            String message = cause.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).contains(PURCHASE_UNIQUE_CONSTRAINT)) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }
}
