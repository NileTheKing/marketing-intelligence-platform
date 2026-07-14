package com.axon.core_service.commandprocessing;

import com.axon.core_service.domain.coupon.Coupon;
import com.axon.core_service.domain.coupon.UserCoupon;
import com.axon.core_service.repository.CouponRepository;
import com.axon.core_service.repository.UserCouponRepository;
import com.axon.messaging.CampaignActivityType;
import com.axon.messaging.dto.CampaignActivityKafkaProducerDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class CouponStrategy implements BatchStrategy {

    private final UserCouponRepository userCouponRepository;
    private final CouponRepository couponRepository;

    @Override
    public CampaignActivityType getType() {
        return CampaignActivityType.COUPON;
    }

    @Override
    @Transactional
    public void process(CampaignActivityKafkaProducerDto message) {
        processBatch(List.of(message));
    }

    @Override
    @Transactional
    public void processBatch(List<CampaignActivityKafkaProducerDto> messages) {
        log.info("Processing Coupon batch size: {}", messages.size());

        // 1. 관련된 Coupon ID 목록 추출 (actionReferenceId 우선, couponId 필드, productId 폴백 순)
        List<Long> couponIds = messages.stream()
                .map(this::resolveCouponId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());

        List<Long> userIds = messages.stream()
                .map(CampaignActivityKafkaProducerDto::getUserId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());

        if (couponIds.isEmpty() || userIds.isEmpty()) {
            log.warn("Coupon batch skipped. couponIds={}, userIds={}", couponIds.size(), userIds.size());
            return;
        }

        // 2. Coupon 엔티티 조회 (Bulk Read)
        Map<Long, Coupon> couponMap = couponRepository.findAllById(couponIds).stream()
                .collect(Collectors.toMap(Coupon::getId, c -> c));

        Set<String> existingKeys = userCouponRepository
                .findAllByUserIdInAndCouponIdIn(userIds, couponIds)
                .stream()
                .map(userCoupon -> couponKey(userCoupon.getUserId(), userCoupon.getCoupon().getId()))
                .collect(Collectors.toSet());
        Set<String> batchKeys = new HashSet<>();

        // 3. UserCoupon 엔티티 생성 (배치 단위 중복 체크 포함)
        List<UserCoupon> userCoupons = messages.stream()
                .map(msg -> {
                    Long couponId = resolveCouponId(msg);
                    Long userId = msg.getUserId();
                    Coupon coupon = couponMap.get(couponId);

                    if (userId == null) {
                        log.warn("User ID is missing for coupon command. couponId={}", couponId);
                        return null;
                    }

                    if (coupon == null) {
                        log.warn("Coupon not found for ID: {}", couponId);
                        return null;
                    }

                    String couponKey = couponKey(userId, couponId);
                    if (existingKeys.contains(couponKey) || !batchKeys.add(couponKey)) {
                        log.info("User {} already has coupon {}", userId, couponId);
                        return null;
                    }

                    return UserCoupon.builder()
                            .userId(userId)
                            .coupon(coupon)
                            .build();
                })
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toList());

        // 4. Bulk Insert
        if (!userCoupons.isEmpty()) {
            userCouponRepository.saveAll(userCoupons);
            log.info("Saved {} user coupons", userCoupons.size());
        }
    }

    private String couponKey(Long userId, Long couponId) {
        return userId + ":" + couponId;
    }

    private Long resolveCouponId(CampaignActivityKafkaProducerDto msg) {
        if (msg.getActionReferenceId() != null) {
            return msg.getActionReferenceId();
        }
        return msg.getCouponId() != null ? msg.getCouponId() : msg.getProductId();
    }
}
