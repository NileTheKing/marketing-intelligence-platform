package com.axon.core_service.scheduler;

import com.axon.core_service.domain.marketing.MarketingRule;
import com.axon.core_service.domain.marketing.RewardType;
import com.axon.core_service.repository.MarketingRuleRepository;
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
    private final MarketingRuleRepository marketingRuleRepository;
    private final RedisTemplate<String, String> redisTemplate;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    private static final long COUPON_TTL_DAYS = 30;

    /**
     * 매 시간 0분에 실행.
     * 활성화된 MarketingRule을 DB에서 로드한 뒤, 규칙별로 행동 조건을 평가하고
     * 임계값을 충족한 유저에게 쿠폰을 발행합니다.
     */
    @Scheduled(cron = "0 0 * * * *")
    public void runBehaviorCouponTrigger() {
        log.info("========== Behavior Coupon Trigger Batch Started ==========");

        List<MarketingRule> activeRules = marketingRuleRepository.findByIsActiveTrue();
        log.info("Loaded {} active marketing rules", activeRules.size());

        for (MarketingRule rule : activeRules) {
            if (rule.getRewardType() != RewardType.COUPON) {
                log.debug("Skipping rule '{}': rewardType={}", rule.getRuleName(), rule.getRewardType());
                continue;
            }
            processRule(rule);
        }

        log.info("========== Behavior Coupon Trigger Batch Completed ==========");
    }

    private void processRule(MarketingRule rule) {
        log.info("Processing rule: id={}, name='{}', behaviorType={}, threshold={}, lookbackDays={}",
                rule.getId(), rule.getRuleName(), rule.getBehaviorType(),
                rule.getThresholdCount(), rule.getLookbackDays());

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime start = now.minusDays(rule.getLookbackDays());

        try {
            Map<Long, List<Long>> highlyEngagedUsers = behaviorEventService.getHighlyEngagedUsersForProduct(
                    start, now, rule.getBehaviorType(), rule.getThresholdCount());

            for (Map.Entry<Long, List<Long>> entry : highlyEngagedUsers.entrySet()) {
                Long userId = entry.getKey();

                for (Long productId : entry.getValue()) {
                    String redisKey = String.format("coupon:trigger:%d:%d:%d", rule.getId(), userId, productId);
                    Boolean isAbsent = redisTemplate.opsForValue()
                            .setIfAbsent(redisKey, "1", COUPON_TTL_DAYS, TimeUnit.DAYS);

                    if (Boolean.TRUE.equals(isAbsent)) {
                        log.info("Triggering coupon: ruleId={}, userId={}, productId={}, couponId={}",
                                rule.getId(), userId, productId, rule.getRewardReferenceId());

                        CampaignActivityKafkaProducerDto message = CampaignActivityKafkaProducerDto.builder()
                                .campaignActivityType(CampaignActivityType.COUPON)
                                .userId(userId)
                                .productId(productId)
                                .couponId(rule.getRewardReferenceId()) // DB에서 로드한 실제 쿠폰 ID
                                .timestamp(System.currentTimeMillis())
                                .build();

                        kafkaTemplate.send(KafkaTopics.CAMPAIGN_ACTIVITY_COMMAND, message);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error processing rule id={} name='{}': {}", rule.getId(), rule.getRuleName(), e.getMessage(), e);
        }
    }
}
