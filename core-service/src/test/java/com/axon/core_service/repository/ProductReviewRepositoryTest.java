package com.axon.core_service.repository;

import com.axon.core_service.domain.review.ProductReview;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
class ProductReviewRepositoryTest {
    @Autowired private ProductReviewRepository reviewRepository;

    @Test
    void databaseEnforcesOneReviewPerPurchaseWithNamedConstraint() {
        LocalDateTime created = LocalDateTime.parse("2026-10-16T10:00:00");
        reviewRepository.saveAndFlush(new ProductReview(44L, 101L, 1L, 3, "one", created));
        assertThatThrownBy(() -> reviewRepository.saveAndFlush(
                new ProductReview(55L, 101L, 2L, 4, "duplicate", created)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("UK_PRODUCT_REVIEW_PURCHASE")
                .hasMessageContaining("PURCHASE_ID");
    }

    @Test
    void returnsStableNewestFirstSlicesAtTimestampTies() {
        LocalDateTime sameTime = LocalDateTime.parse("2026-10-16T10:00:00");
        reviewRepository.save(new ProductReview(44L, 101L, 1L, 3, "one", sameTime));
        reviewRepository.save(new ProductReview(99L, 199L, 9L, 5, "other product newest", sameTime.plusDays(1)));
        reviewRepository.save(new ProductReview(44L, 102L, 2L, 4, "two", sameTime));
        reviewRepository.save(new ProductReview(44L, 103L, 3L, 5, "three", sameTime));
        reviewRepository.flush();

        var first = reviewRepository.findByProductId(44L,
                PageRequest.of(0, 2, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))));
        var second = reviewRepository.findByProductId(44L,
                PageRequest.of(1, 2, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))));

        assertThat(first.getContent()).extracting(ProductReview::getContent).containsExactly("three", "two");
        assertThat(first.hasNext()).isTrue();
        assertThat(second.getContent()).extracting(ProductReview::getContent).containsExactly("one");
        assertThat(second.hasNext()).isFalse();
    }
}
