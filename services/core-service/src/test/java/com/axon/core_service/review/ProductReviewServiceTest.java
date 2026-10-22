package com.axon.core_service.review;

import com.axon.core_service.review.dto.ProductReviewCreateRequest;
import com.axon.core_service.domain.purchase.Purchase;
import com.axon.core_service.domain.purchase.PurchaseStatus;
import com.axon.core_service.review.domain.ProductReview;
import com.axon.core_service.exception.BusinessConflictException;
import com.axon.core_service.exception.InvalidRequestException;
import com.axon.core_service.exception.ResourceNotFoundException;
import com.axon.core_service.catalog.ProductRepository;
import com.axon.core_service.repository.PurchaseRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.SliceImpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductReviewServiceTest {
    private ProductRepository productRepository;
    private PurchaseRepository purchaseRepository;
    private ProductReviewRepository reviewRepository;
    private ProductReviewService service;

    @BeforeEach
    void setUp() {
        productRepository = mock(ProductRepository.class);
        purchaseRepository = mock(PurchaseRepository.class);
        reviewRepository = mock(ProductReviewRepository.class);
        service = new ProductReviewService(productRepository, purchaseRepository, reviewRepository);
    }

    @Test
    void createsTrimmedReviewForConfirmedPurchaseOwnedByCaller() {
        when(productRepository.existsById(8L)).thenReturn(true);
        when(purchaseRepository.findById(15L)).thenReturn(Optional.of(purchase(3L, 8L)));
        when(reviewRepository.existsByPurchaseId(15L)).thenReturn(false);
        when(reviewRepository.saveAndFlush(any(ProductReview.class))).thenAnswer(invocation -> {
            ProductReview review = invocation.getArgument(0);
            return new ProductReview(8L, 15L, 3L, 5, review.getContent(), review.getCreatedAt());
        });
        // The DTO mapping is exercised through the saved domain object below; JPA assigns the id in production.
        var response = service.create(8L, 3L, request(15L, 5, "  nice fabric  "));
        assertThat(response.content()).isEqualTo("nice fabric");
        assertThat(response.rating()).isEqualTo(5);
        verify(reviewRepository).existsByPurchaseId(15L);
    }

    @Test
    void rejectsMissingProductOrPurchase() {
        when(productRepository.existsById(8L)).thenReturn(false);
        assertThatThrownBy(() -> service.create(8L, 3L, request(15L, 5, "ok")))
                .isInstanceOf(ResourceNotFoundException.class);

        when(productRepository.existsById(8L)).thenReturn(true);
        when(purchaseRepository.findById(15L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.create(8L, 3L, request(15L, 5, "ok")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void rejectsPurchaseOwnerProductAndStatusMismatch() {
        when(productRepository.existsById(8L)).thenReturn(true);
        when(purchaseRepository.findById(15L)).thenReturn(Optional.of(purchase(4L, 8L)));
        assertThatThrownBy(() -> service.create(8L, 3L, request(15L, 5, "ok")))
                .isInstanceOf(InvalidRequestException.class);

        when(purchaseRepository.findById(15L)).thenReturn(Optional.of(purchase(3L, 9L)));
        assertThatThrownBy(() -> service.create(8L, 3L, request(15L, 5, "ok")))
                .isInstanceOf(InvalidRequestException.class);

        Purchase cancelled = purchase(3L, 8L);
        cancelled.cancel("cancel", Instant.now());
        assertThat(cancelled.getStatus()).isEqualTo(PurchaseStatus.CANCELLED);
        when(purchaseRepository.findById(15L)).thenReturn(Optional.of(cancelled));
        assertThatThrownBy(() -> service.create(8L, 3L, request(15L, 5, "ok")))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void enforcesTrimmedContentAndRejectsExistingReview() {
        when(productRepository.existsById(8L)).thenReturn(true);
        when(purchaseRepository.findById(15L)).thenReturn(Optional.of(purchase(3L, 8L)));
        assertThatThrownBy(() -> service.create(8L, 3L, request(15L, 5, "  ")))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.create(8L, 3L, request(15L, 5, "x".repeat(1001))))
                .isInstanceOf(InvalidRequestException.class);
        when(reviewRepository.existsByPurchaseId(15L)).thenReturn(true);
        assertThatThrownBy(() -> service.create(8L, 3L, request(15L, 5, "ok")))
                .isInstanceOf(BusinessConflictException.class);
    }

    @Test
    void mapsOnlyNamedPurchaseUniqueConstraintRaceToConflict() {
        when(productRepository.existsById(8L)).thenReturn(true);
        when(purchaseRepository.findById(15L)).thenReturn(Optional.of(purchase(3L, 8L)));
        when(reviewRepository.existsByPurchaseId(15L)).thenReturn(false);
        when(reviewRepository.saveAndFlush(any(ProductReview.class)))
                .thenThrow(new DataIntegrityViolationException("Duplicate entry for uk_product_review_purchase"));
        assertThatThrownBy(() -> service.create(8L, 3L, request(15L, 5, "ok")))
                .isInstanceOf(BusinessConflictException.class);

        when(reviewRepository.saveAndFlush(any(ProductReview.class)))
                .thenThrow(new DataIntegrityViolationException("some other constraint"));
        assertThatThrownBy(() -> service.create(8L, 3L, request(15L, 5, "ok")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void usesStableNewestFirstSliceWithoutCount() {
        when(productRepository.existsById(8L)).thenReturn(true);
        LocalDateTime same = LocalDateTime.parse("2026-10-16T10:00:00");
        var rows = List.of(new ProductReview(8L, 22L, 3L, 4, "newer id", same),
                new ProductReview(8L, 21L, 4L, 5, "older id", same));
        when(reviewRepository.findByProductId(any(), any())).thenReturn(new SliceImpl<>(rows,
                PageRequest.of(0, 2), true));
        var result = service.findByProduct(8L, 0, 2);
        assertThat(result.items()).extracting(item -> item.content()).containsExactly("newer id", "older id");
        assertThat(result.hasNext()).isTrue();
        assertThat(result.page()).isZero();
        verify(reviewRepository).findByProductId(org.mockito.ArgumentMatchers.eq(8L),
                org.mockito.ArgumentMatchers.argThat(page -> page.getPageSize() == 2
                        && page.getSort().getOrderFor("createdAt").isDescending()
                        && page.getSort().getOrderFor("id").isDescending()));
    }

    @Test
    void rejectsInvalidPageAndSize() {
        when(productRepository.existsById(8L)).thenReturn(true);
        assertThatThrownBy(() -> service.findByProduct(8L, -1, 20)).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.findByProduct(8L, 0, 0)).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.findByProduct(8L, 0, 101)).isInstanceOf(InvalidRequestException.class);
    }

    private static Purchase purchase(Long userId, Long productId) {
        Purchase purchase = new Purchase(userId, productId, null, com.axon.core_service.domain.purchase.PurchaseType.SHOP,
                BigDecimal.ONE, 1, Instant.now());
        ReflectionTestUtils.setField(purchase, "id", 15L);
        return purchase;
    }

    private static ProductReviewCreateRequest request(Long purchaseId, Integer rating, String content) {
        return new ProductReviewCreateRequest(purchaseId, rating, content);
    }
}
