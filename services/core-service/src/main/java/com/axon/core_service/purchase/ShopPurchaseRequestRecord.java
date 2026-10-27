package com.axon.core_service.purchase;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Table(name = "shop_purchase_requests", uniqueConstraints = {
        @UniqueConstraint(name = "uk_shop_request_user_request", columnNames = {"user_id", "request_id"}),
        @UniqueConstraint(name = "uk_shop_request_purchase", columnNames = "purchase_id")})
public class ShopPurchaseRequestRecord {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(name = "request_id", nullable = false, length = 36) private String requestId;
    @Column(name = "product_id", nullable = false) private Long productId;
    @Column(name = "user_coupon_id") private Long userCouponId;
    @Column(nullable = false) private int quantity;
    @Column(name = "purchase_id") private Long purchaseId;
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;
    protected ShopPurchaseRequestRecord() { }
    ShopPurchaseRequestRecord(Long userId, ShopPurchaseRequest request) {
        this.userId = userId; this.requestId = request.requestId().toString();
        this.productId = request.productId(); this.userCouponId = request.userCouponId();
        this.quantity = 1; this.createdAt = LocalDateTime.now();
    }
    boolean matches(ShopPurchaseRequest request) {
        return Objects.equals(productId, request.productId()) && Objects.equals(userCouponId, request.userCouponId())
                && quantity == 1;
    }
    Long purchaseId() { return purchaseId; }
    void complete(Long id) { this.purchaseId = Objects.requireNonNull(id); }
}
