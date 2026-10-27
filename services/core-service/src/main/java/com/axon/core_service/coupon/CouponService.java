package com.axon.core_service.coupon;

import com.axon.core_service.coupon.domain.Coupon;
import com.axon.core_service.coupon.domain.CouponStatus;
import com.axon.core_service.coupon.domain.UserCoupon;
import com.axon.core_service.coupon.dto.ApplicableCouponDto;
import com.axon.core_service.coupon.dto.CouponRequest;
import com.axon.core_service.coupon.dto.CouponResponse;
import com.axon.core_service.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CouponService {

    private final CouponRepository couponRepository;
    private final UserCouponRepository userCouponRepository;

    public List<CouponResponse> getAllCoupons() {
        return couponRepository.findAll().stream()
                .map(CouponResponse::new)
                .collect(Collectors.toList());
    }

    @Transactional
    public Long createCoupon(CouponRequest request) {
        Coupon coupon = Coupon.builder()
                .name(request.getCouponName())
                .discountAmount(request.getDiscountAmount())
                .discountRate(request.getDiscountRate())
                .minOrderAmount(request.getMinOrderAmount())
                .targetCategory(request.getTargetCategory())
                .startDate(request.getStartDate())
                .endDate(request.getEndDate())
                .build();

        couponRepository.save(coupon);
        return coupon.getId();
    }

    @Transactional
    public Long updateCoupon(Long id, CouponRequest request) {
        Coupon coupon = couponRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("coupon", id));

        coupon.update(
                request.getCouponName(),
                request.getDiscountAmount(),
                request.getDiscountRate(),
                request.getMinOrderAmount(),
                request.getTargetCategory(),
                request.getStartDate(),
                request.getEndDate());

        return coupon.getId();
    }

    @Transactional
    public void deleteCoupon(Long id) {
        Coupon coupon = couponRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("coupon", id));
        couponRepository.delete(coupon);
    }

    /**
     * 사용자가 특정 상품에 적용 가능한 쿠폰 목록 조회
     *
     * @param userId           사용자 ID
     * @param productPrice     상품 가격
     * @param productCategory  상품 카테고리
     * @return 적용 가능한 쿠폰 리스트
     */
    public List<ApplicableCouponDto> getApplicableCoupons(Long userId, BigDecimal productPrice, String productCategory) {
        return getApplicableCoupons(userId, productPrice, productCategory, productPrice);
    }

    public List<ApplicableCouponDto> getApplicableCoupons(Long userId, BigDecimal productPrice,
            String productCategory, BigDecimal sellingPrice) {
        List<UserCoupon> userCoupons = userCouponRepository.findAllByUserId(userId);

        LocalDateTime now = LocalDateTime.now();

        return userCoupons.stream()
                .filter(uc -> isApplicable(uc, productPrice, productCategory, now))
                .map(uc -> toDto(uc, productPrice, sellingPrice))
                .collect(Collectors.toList());
    }

    private boolean isApplicable(UserCoupon userCoupon, BigDecimal productPrice,
            String productCategory, LocalDateTime now) {
        return userCoupon.getStatus() == CouponStatus.ISSUED
                && userCoupon.getCoupon().isApplicable(productPrice, productCategory, now);
    }

    /**
     * UserCoupon을 DTO로 변환하면서 할인 금액 계산
     */
    private ApplicableCouponDto toDto(UserCoupon userCoupon, BigDecimal productPrice, BigDecimal sellingPrice) {
        Coupon coupon = userCoupon.getCoupon();
        BigDecimal calculatedDiscount = coupon.calculateDiscount(productPrice).min(sellingPrice);

        return ApplicableCouponDto.builder()
                .userCouponId(userCoupon.getId())
                .couponId(coupon.getId())
                .couponName(coupon.getCouponName())
                .discountAmount(coupon.getDiscountAmount())
                .discountRate(coupon.getDiscountRate())
                .minOrderAmount(coupon.getMinOrderAmount())
                .targetCategory(coupon.getTargetCategory())
                .calculatedDiscount(calculatedDiscount)
                .build();
    }

    /**
     * 쿠폰 사용 처리
     *
     * @param userCouponId 사용할 UserCoupon ID
     * @param userId 사용자 ID (검증용)
     * @param productPrice 서버에서 조회한 쿠폰 적용 전 상품 금액
     * @param productCategory 서버에서 조회한 상품 카테고리
     */
    @Transactional
    public BigDecimal useCoupon(Long userCouponId, Long userId, BigDecimal productPrice, String productCategory) {
        UserCoupon userCoupon = userCouponRepository.findByIdForUpdate(userCouponId)
                .orElseThrow(() -> new InvalidCouponException());

        // 소유자 확인
        if (!userCoupon.getUserId().equals(userId)) {
            throw new InvalidCouponException();
        }

        // 이미 사용된 쿠폰인지 확인
        if (userCoupon.getStatus() != CouponStatus.ISSUED) {
            throw new CouponAlreadyUsedException();
        }

        if (!isApplicable(userCoupon, productPrice, productCategory, LocalDateTime.now())) {
            throw new CouponNotApplicableException();
        }

        // 쿠폰 사용 처리
        userCoupon.use();
        log.info("Coupon {} used by user {}", userCouponId, userId);
        return userCoupon.getCoupon().calculateDiscount(productPrice);
    }

    public static class InvalidCouponException extends IllegalArgumentException {
        public InvalidCouponException() { super("Invalid coupon"); }
    }
    public static class CouponAlreadyUsedException extends IllegalStateException {
        public CouponAlreadyUsedException() { super("Coupon already used or invalid"); }
    }
    public static class CouponNotApplicableException extends IllegalArgumentException {
        public CouponNotApplicableException() { super("Coupon is not applicable to this purchase"); }
    }
}
