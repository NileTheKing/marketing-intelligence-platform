package com.axon.core_service.commandprocessing;

import com.axon.core_service.domain.coupon.Coupon;
import com.axon.core_service.domain.coupon.UserCoupon;
import com.axon.core_service.repository.CouponRepository;
import com.axon.core_service.repository.UserCouponRepository;
import com.axon.core_service.service.MarketingActionExecutionService;
import com.axon.messaging.CampaignActivityType;
import com.axon.messaging.dto.CampaignActivityKafkaProducerDto;
import com.axon.messaging.topic.KafkaTopics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.CompletionException;

@Slf4j
@Component
public class CouponStrategy implements BatchStrategy {

    private final UserCouponRepository userCouponRepository;
    private final CouponRepository couponRepository;
    private final MarketingActionExecutionService executionService;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    public CouponStrategy(UserCouponRepository userCouponRepository,
                          CouponRepository couponRepository,
                          MarketingActionExecutionService executionService,
                          KafkaTemplate<String, Object> kafkaTemplate) {
        this.userCouponRepository = userCouponRepository;
        this.couponRepository = couponRepository;
        this.executionService = executionService;
        this.kafkaTemplate = kafkaTemplate;
    }

    public CouponStrategy(UserCouponRepository userCouponRepository,
                          CouponRepository couponRepository,
                          MarketingActionExecutionService executionService) {
        this(userCouponRepository, couponRepository, executionService, null);
    }

    /** Backward-compatible constructor for focused strategy tests. */
    public CouponStrategy(UserCouponRepository userCouponRepository, CouponRepository couponRepository) {
        this(userCouponRepository, couponRepository, null);
    }

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

        messages.forEach(this::executionServiceRecordAttempt);

        // 1. 관련된 Coupon ID 목록 추출 (actionReferenceId 우선, couponId 필드, productId 폴백 순)
        List<Long> couponIds = messages.stream()
                .map(this::resolveCouponId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());

        // 2. Coupon 엔티티 조회 (Bulk Read)
        Map<Long, Coupon> couponMap = couponIds.isEmpty()
                ? Map.of()
                : couponRepository.findAllById(couponIds).stream()
                        .collect(Collectors.toMap(Coupon::getId, c -> c));

        List<CampaignActivityKafkaProducerDto> validMessages = new ArrayList<>();
        for (CampaignActivityKafkaProducerDto message : messages) {
            Long couponId = resolveCouponId(message);
            if (message.getUserId() == null) {
                routeInvalidCommand(message, "Coupon command userId is missing");
            } else if (couponId == null) {
                routeInvalidCommand(message, "Coupon command couponId is missing");
            } else if (!couponMap.containsKey(couponId)) {
                routeInvalidCommand(message, "Coupon not found for ID: " + couponId);
            } else {
                validMessages.add(message);
            }
        }

        if (validMessages.isEmpty()) {
            return;
        }

        List<Long> validUserIds = validMessages.stream()
                .map(CampaignActivityKafkaProducerDto::getUserId)
                .distinct()
                .collect(Collectors.toList());
        List<Long> validCouponIds = validMessages.stream()
                .map(this::resolveCouponId)
                .distinct()
                .collect(Collectors.toList());

        Set<String> existingKeys = userCouponRepository
                .findAllByUserIdInAndCouponIdIn(validUserIds, validCouponIds)
                .stream()
                .map(userCoupon -> couponKey(userCoupon.getUserId(), userCoupon.getCoupon().getId()))
                .collect(Collectors.toSet());
        Set<String> batchKeys = new HashSet<>();

        // 3. UserCoupon 엔티티 생성 (배치 단위 중복 체크 포함)
        List<UserCoupon> userCoupons = validMessages.stream()
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

        validMessages.forEach(this::executionServiceMarkSucceeded);
    }

    private void executionServiceRecordAttempt(CampaignActivityKafkaProducerDto message) {
        if (executionService != null) {
            executionService.recordAttempt(message.getDispatchId());
        }
    }

    private void executionServiceMarkSucceeded(CampaignActivityKafkaProducerDto message) {
        if (executionService != null) {
            executionService.markSucceeded(message.getDispatchId());
        }
    }

    private void routeInvalidCommand(CampaignActivityKafkaProducerDto message, String reason) {
        message.setFailureReason(reason);
        if (kafkaTemplate == null) {
            throw new OffsetCommitBlockedException("Coupon command DLT producer is unavailable",
                    new IllegalStateException(reason));
        }
        try {
            kafkaTemplate.send(KafkaTopics.CAMPAIGN_ACTIVITY_COMMAND_DLT, message).join();
        } catch (CompletionException e) {
            throw new OffsetCommitBlockedException("Coupon command DLT publish failed", e);
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
