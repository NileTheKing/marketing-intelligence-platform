package com.axon.core_service.scheduler;

import com.axon.core_service.domain.marketing.MarketingAction;
import com.axon.core_service.domain.marketing.MarketingRule;
import com.axon.core_service.domain.marketing.RewardType;
import com.axon.core_service.repository.MarketingActionRepository;
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
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class BehaviorTriggerScheduler {

    private final BehaviorEventService behaviorEventService;
    private final MarketingRuleRepository marketingRuleRepository;
    private final MarketingActionRepository marketingActionRepository;
    private final RedisTemplate<String, String> redisTemplate;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    /**
     * 매 시간 0분에 실행.
     * 활성화된 MarketingRule을 DB에서 로드한 뒤, 규칙별로 행동 조건을 평가하고
     * 임계값을 충족한 유저에게 각 룰의 활성 액션(coupon/webhook)을 독립적으로 발행합니다.
     */
    @Scheduled(cron = "0 0 * * * *")
    public void runBehaviorCouponTrigger() {
        log.info("========== Behavior Coupon Trigger Batch Started ==========");

        List<MarketingRule> activeRules = marketingRuleRepository.findByIsActiveTrue();
        log.info("Loaded {} active marketing rules", activeRules.size());

        List<Long> ruleIds = activeRules.stream().map(MarketingRule::getId).toList();
        Map<Long, List<MarketingAction>> actionsByRuleId = marketingActionRepository
                .findByMarketingRuleIdInAndIsActiveTrue(ruleIds).stream()
                .collect(Collectors.groupingBy(action -> action.getMarketingRule().getId()));

        for (MarketingRule rule : activeRules) {
            List<MarketingAction> actions = actionsByRuleId.get(rule.getId());
            if (actions == null || actions.isEmpty()) {
                log.debug("Skipping rule '{}': no active actions", rule.getRuleName());
                continue;
            }
            processRule(rule, actions);
        }

        log.info("========== Behavior Coupon Trigger Batch Completed ==========");
    }

    private void processRule(MarketingRule rule, List<MarketingAction> actions) {
        log.info("Processing rule: id={}, name='{}', behaviorType={}, threshold={}, lookbackDays={}",
                rule.getId(), rule.getRuleName(), rule.getBehaviorType(),
                rule.getThresholdCount(), rule.getLookbackDays());

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime start = now.minusDays(rule.getLookbackDays());

        try {
            Map<Long, List<Long>> highlyEngagedUsers = behaviorEventService.getHighlyEngagedUsersForProduct(
                    start, now, rule.getBehaviorType(), rule.getThresholdCount(),
                    rule.getTargetProductId(), rule.getPropertyConditions());

            for (Map.Entry<Long, List<Long>> entry : highlyEngagedUsers.entrySet()) {
                Long userId = entry.getKey();

                for (Long productId : entry.getValue()) {
                    for (MarketingAction action : actions) {
                        triggerAction(rule, action, userId, productId);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error processing rule id={} name='{}': {}", rule.getId(), rule.getRuleName(), e.getMessage(), e);
        }
    }

    private void triggerAction(MarketingRule rule, MarketingAction action, Long userId, Long productId) {
        String redisKey = triggerKey(action, userId, productId);
        Boolean isAbsent = redisTemplate.opsForValue()
                .setIfAbsent(redisKey, "1", triggerDedupTtlDays(rule), TimeUnit.DAYS);

        if (!Boolean.TRUE.equals(isAbsent)) {
            return;
        }

        log.info("Triggering action: type={}, ruleId={}, actionId={}, userId={}, productId={}, referenceId={}",
                action.getActionType(), rule.getId(), action.getId(), userId, productId, action.getReferenceId());

        CampaignActivityKafkaProducerDto message = buildRewardMessage(rule, action, userId, productId);

        try {
            kafkaTemplate.send(KafkaTopics.CAMPAIGN_ACTIVITY_COMMAND, message)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.error("Kafka send failed, releasing dedup key: actionId={}, userId={}, productId={}",
                                    action.getId(), userId, productId, ex);
                            redisTemplate.delete(redisKey);
                        }
                    });
        } catch (Exception ex) {
            log.error("Kafka send threw synchronously, releasing dedup key: actionId={}, userId={}, productId={}",
                    action.getId(), userId, productId, ex);
            redisTemplate.delete(redisKey);
        }
    }

    private String triggerKey(MarketingAction action, Long userId, Long productId) {
        return String.format("marketing:action-trigger:%d:%d:%d", action.getId(), userId, productId);
    }

    private long triggerDedupTtlDays(MarketingRule rule) {
        return rule.getDedupTtlDays() > 0 ? rule.getDedupTtlDays() : 30L;
    }

    private CampaignActivityKafkaProducerDto buildRewardMessage(MarketingRule rule, MarketingAction action,
                                                                  Long userId, Long productId) {
        CampaignActivityType type = action.getActionType() == RewardType.WEBHOOK
                ? CampaignActivityType.WEBHOOK
                : CampaignActivityType.COUPON;

        return CampaignActivityKafkaProducerDto.builder()
                .campaignActivityType(type)
                .userId(userId)
                .productId(productId)
                .marketingRuleId(rule.getId())
                .marketingActionId(action.getId())
                .actionReferenceId(action.getReferenceId())
                .timestamp(System.currentTimeMillis())
                .build();
    }
}
