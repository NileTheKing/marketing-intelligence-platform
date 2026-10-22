package com.axon.core_service.service;

import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import com.axon.core_service.coupon.domain.CouponStatus;
import com.axon.core_service.coupon.domain.UserCoupon;
import com.axon.core_service.domain.dto.customer.CustomerBehaviorEvent;
import com.axon.core_service.domain.dto.customer.CustomerProfileResponse;
import com.axon.core_service.domain.dto.customer.CustomerProfileResponse.PurchaseItem;
import com.axon.core_service.domain.product.Product;
import com.axon.core_service.domain.purchase.Purchase;
import com.axon.core_service.domain.purchase.PurchaseStatus;
import com.axon.core_service.domain.user.User;
import com.axon.core_service.domain.user.UserSummary;
import com.axon.core_service.exception.ResourceNotFoundException;
import com.axon.core_service.repository.ProductRepository;
import com.axon.core_service.repository.PurchaseRepository;
import com.axon.core_service.coupon.UserCouponRepository;
import com.axon.core_service.repository.UserRepository;
import com.axon.core_service.repository.UserSummaryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CustomerProfileService {

    static final int RECENT_PURCHASE_LIMIT = 10;
    static final int RECENT_EVENT_LIMIT = 50;

    private final UserRepository userRepository;
    private final UserSummaryRepository userSummaryRepository;
    private final PurchaseRepository purchaseRepository;
    private final UserCouponRepository userCouponRepository;
    private final ProductRepository productRepository;
    private final BehaviorEventService behaviorEventService;

    @Transactional(readOnly = true)
    public CustomerProfileResponse getProfile(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
        Optional<UserSummary> summary = userSummaryRepository.findById(userId);

        List<Purchase> purchases = purchaseRepository.findByUserId(userId);
        List<Purchase> confirmed = purchases.stream()
                .filter(purchase -> purchase.getStatus() == PurchaseStatus.CONFIRMED)
                .toList();
        BigDecimal confirmedRevenue = confirmed.stream()
                .map(CustomerProfileService::amountOf)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<UserCoupon> coupons = userCouponRepository.findAllByUserId(userId);
        long usedCouponCount = coupons.stream()
                .filter(coupon -> coupon.getStatus() == CouponStatus.USED)
                .count();

        return new CustomerProfileResponse(
                user.getId(),
                user.getName(),
                user.getEmail(),
                user.getGrade() != null ? user.getGrade().name() : null,
                summary.map(UserSummary::getRfmSegment).orElse(null),
                summary.map(UserSummary::getLastPurchaseAt).orElse(null),
                summary.map(UserSummary::getLastLoginAt).orElse(null),
                confirmed.size(),
                confirmedRevenue,
                coupons.size(),
                usedCouponCount,
                recentPurchases(purchases, coupons),
                recentEvents(userId));
    }

    private List<PurchaseItem> recentPurchases(List<Purchase> purchases, List<UserCoupon> coupons) {
        List<Purchase> recent = purchases.stream()
                .sorted(Comparator.comparing(Purchase::getPurchaseAt).reversed())
                .limit(RECENT_PURCHASE_LIMIT)
                .toList();
        Map<Long, String> productNames = productRepository
                .findAllById(recent.stream().map(Purchase::getProductId).distinct().toList()).stream()
                .collect(Collectors.toMap(Product::getId, Product::getProductName));
        Map<Long, UserCoupon> couponsById = coupons.stream()
                .collect(Collectors.toMap(UserCoupon::getId, Function.identity()));

        return recent.stream()
                .map(purchase -> new PurchaseItem(
                        purchase.getId(),
                        productNames.get(purchase.getProductId()),
                        amountOf(purchase),
                        purchase.getStatus().name(),
                        couponNameOf(couponsById.get(purchase.getUserCouponId())),
                        purchase.getPurchaseAt()))
                .toList();
    }

    /** Behavior is supplementary: an Elasticsearch failure leaves the timeline empty instead of failing the page. */
    private List<CustomerBehaviorEvent> recentEvents(Long userId) {
        try {
            return behaviorEventService.getRecentEventsByUser(userId, RECENT_EVENT_LIMIT);
        } catch (IOException | ElasticsearchException e) {
            log.error("Failed to load behavior events for userId={}", userId, e);
            return List.of();
        }
    }

    private static BigDecimal amountOf(Purchase purchase) {
        return purchase.getPrice().multiply(BigDecimal.valueOf(purchase.getQuantity()));
    }

    private static String couponNameOf(UserCoupon userCoupon) {
        return userCoupon != null && userCoupon.getCoupon() != null ? userCoupon.getCoupon().getCouponName() : null;
    }
}
