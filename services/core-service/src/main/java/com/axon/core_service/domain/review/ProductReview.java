package com.axon.core_service.domain.review;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "product_reviews",
        uniqueConstraints = @UniqueConstraint(name = "uk_product_review_purchase", columnNames = "purchase_id"),
        indexes = @Index(name = "idx_product_review_product_created", columnList = "product_id, created_at"))
@Getter
@NoArgsConstructor
public class ProductReview {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "purchase_id", nullable = false)
    private Long purchaseId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Integer rating;

    @Column(nullable = false, length = 1000)
    private String content;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public ProductReview(Long productId, Long purchaseId, Long userId, Integer rating,
                         String content, LocalDateTime createdAt) {
        this.productId = productId;
        this.purchaseId = purchaseId;
        this.userId = userId;
        this.rating = rating;
        this.content = content;
        this.createdAt = createdAt;
    }
}
