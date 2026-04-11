package com.axon.core_service.scheduler;

import com.axon.core_service.service.BehaviorEventService;
import com.axon.messaging.CampaignActivityType;
import com.axon.messaging.dto.CampaignActivityKafkaProducerDto;
import com.axon.messaging.topic.KafkaTopics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class BehaviorTriggerScheduler {

    private final BehaviorEventService behaviorEventService;
    private final RedisTemplate<String, String> redisTemplate;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    private static final String TRIGGER_TYPE = "PAGE_VIEW";
    private static final int VIEW_THRESHOLD = 3;
    private static final int LOOKBACK_DAYS = 7;
    private static final long COUPON_TTL_DAYS = 30;

    // 매 시간 0분에 실행
    @Scheduled(cron = "0 0 * * * *")
    public void runBehaviorCouponTrigger() {
        log.info("========== Behavior Coupon Trigger Batch Started ==========");
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime start = now.minusDays(LOOKBACK_DAYS);

        try {
            // 1. ES를 통한 타겟 유저/상품 추출
            Map<Long, List<Long>> highlyEngagedUsers = behaviorEventService.getHighlyEngagedUsersForProduct(
                    start, now, TRIGGER_TYPE, VIEW_THRESHOLD);

            for (Map.Entry<Long, List<Long>> entry : highlyEngagedUsers.entrySet()) {
                Long userId = entry.getKey();

                for (Long productId : entry.getValue()) {
                    // 2. Redis를 통한 중복 발급 방지 체크
                    String redisKey = String.format("coupon:trigger:%d:%d", userId, productId);
                    Boolean isAbsent = redisTemplate.opsForValue().setIfAbsent(redisKey, "1", COUPON_TTL_DAYS, TimeUnit.DAYS);

                    if (Boolean.TRUE.equals(isAbsent)) {
                        // 3. 중복이 아니라면 쿠폰 발급 (카프카 발행)
                        log.info("Triggering coupon for user {} on product {}", userId, productId);
                        
                        // 명시적으로 couponId 세팅 (MVP 구조상 productId를 쿠폰아이디로 1:1 매핑한다고 가정)
                        Long couponId = productId; 

                        CampaignActivityKafkaProducerDto message = CampaignActivityKafkaProducerDto.builder()
                                .campaignActivityType(CampaignActivityType.COUPON)
                                .userId(userId)
                                .productId(productId) // 기존 트리거 맥락
                                .couponId(couponId)   // 명시적 쿠폰 발급
                                .timestamp(System.currentTimeMillis())
                                .build();

                        kafkaTemplate.send(KafkaTopics.CAMPAIGN_ACTIVITY_COMMAND, message);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error running Behavior Coupon Trigger Batch", e);
        }

        log.info("========== Behavior Coupon Trigger Batch Completed ==========");
    }
}
