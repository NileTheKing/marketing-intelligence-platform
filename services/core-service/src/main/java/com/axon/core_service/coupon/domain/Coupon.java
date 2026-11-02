package com.axon.core_service.coupon.domain;

import com.axon.core_service.exception.InvalidRequestException;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

@Entity
@Table(name = "coupons")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Coupon {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "coupon_name", nullable = false)
    private String couponName;

    @Column(name = "discount_amount") // 정액 할인
    private BigDecimal discountAmount;

    @Column(name = "discount_rate") // 정률 할인 (%)
    private Integer discountRate;

    @Column(name = "minOrderAmount") // 최소 주문 금액
    private BigDecimal minOrderAmount;

    @Column(name = "target_category")
    private String targetCategory; // 적용 가능 카테고리 (Nullable)

    @Column(name = "start_date", nullable = false)
    private LocalDateTime startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDateTime endDate;

    @Builder
    public Coupon(String name, BigDecimal discountAmount, Integer discountRate,
            BigDecimal minOrderAmount, String targetCategory,
            LocalDateTime startDate, LocalDateTime endDate) {
        validate(name, discountAmount, discountRate, minOrderAmount, startDate, endDate);
        this.couponName = name;
        this.discountAmount = discountAmount;
        this.discountRate = discountRate;
        this.minOrderAmount = minOrderAmount;
        this.targetCategory = targetCategory;
        this.startDate = startDate;
        this.endDate = endDate;
    }

    public boolean isApplicable(BigDecimal productPrice, String productCategory, LocalDateTime now) {
        if (now.isBefore(startDate) || now.isAfter(endDate)) {
            return false;
        }
        if (minOrderAmount != null && productPrice.compareTo(minOrderAmount) < 0) {
            return false;
        }
        return targetCategory == null || targetCategory.isEmpty()
                || targetCategory.equalsIgnoreCase(productCategory);
    }

    // 기존 정책상 정률 할인은 상품 정상가를 기준으로 원 단위까지 내림한다.
    public BigDecimal calculateDiscount(BigDecimal productPrice) {
        BigDecimal discount = BigDecimal.ZERO;
        if (discountAmount != null) {
            discount = discountAmount;
        } else if (discountRate != null) {
            discount = productPrice.multiply(BigDecimal.valueOf(discountRate))
                    .divide(BigDecimal.valueOf(100), 0, RoundingMode.DOWN);
        }
        return discount.min(productPrice);
    }

    public void update(String name, BigDecimal discountAmount, Integer discountRate,
            BigDecimal minOrderAmount, String targetCategory,
            LocalDateTime startDate, LocalDateTime endDate) {
        validate(name, discountAmount, discountRate, minOrderAmount, startDate, endDate);
        this.couponName = name;
        this.discountAmount = discountAmount;
        this.discountRate = discountRate;
        this.minOrderAmount = minOrderAmount;
        this.targetCategory = targetCategory;
        this.startDate = startDate;
        this.endDate = endDate;
    }

    private static void validate(String name, BigDecimal discountAmount, Integer discountRate,
            BigDecimal minOrderAmount, LocalDateTime startDate, LocalDateTime endDate) {
        if (name == null || name.trim().isEmpty()) {
            throw new InvalidRequestException("쿠폰 이름은 필수입니다.");
        }
        if (discountAmount == null && discountRate == null) {
            throw new InvalidRequestException("할인 금액 또는 할인율 중 하나는 필수입니다.");
        }
        if (discountAmount != null && discountRate != null) {
            throw new InvalidRequestException("할인 금액과 할인율을 동시에 설정할 수 없습니다.");
        }
        if (discountAmount != null
                && discountAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidRequestException("할인 금액은 0보다 커야 합니다.");
        }
        if (discountRate != null
                && (discountRate <= 0 || discountRate > 100)) {
            throw new InvalidRequestException("할인율은 1에서 100 사이여야 합니다.");
        }
        if (minOrderAmount != null
                && minOrderAmount.compareTo(BigDecimal.ZERO) < 0) {
            throw new InvalidRequestException("최소 주문 금액은 음수일 수 없습니다.");
        }
        if (startDate == null || endDate == null) {
            throw new InvalidRequestException("시작 날짜와 종료 날짜는 필수입니다.");
        }
        if (!endDate.isAfter(startDate)) {
            throw new InvalidRequestException("종료 날짜는 시작 날짜보다 느려야 합니다.");
        }
    }

}
