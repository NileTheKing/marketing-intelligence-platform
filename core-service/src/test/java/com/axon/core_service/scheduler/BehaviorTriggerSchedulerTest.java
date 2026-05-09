package com.axon.core_service.scheduler;

import com.axon.core_service.domain.marketing.MarketingRule;
import com.axon.core_service.domain.marketing.RewardType;
import com.axon.core_service.repository.MarketingRuleRepository;
import com.axon.core_service.service.BehaviorEventService;
import com.axon.messaging.topic.KafkaTopics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BehaviorTriggerSchedulerTest {

    @Mock
    private BehaviorEventService behaviorEventService;

    @Mock
    private MarketingRuleRepository marketingRuleRepository;

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    @InjectMocks
    private BehaviorTriggerScheduler scheduler;

    private MarketingRule couponRule(Long ruleId) {
        MarketingRule rule = MarketingRule.builder()
                .ruleName("test-rule")
                .behaviorType("PAGE_VIEW")
                .thresholdCount(3)
                .lookbackDays(7)
                .rewardType(RewardType.COUPON)
                .rewardReferenceId(999L)
                .isActive(true)
                .build();
        // reflection으로 id 세팅 (GeneratedValue라 Builder에 없음)
        try {
            var f = MarketingRule.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(rule, ruleId);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return rule;
    }

    @Test
    @DisplayName("조회 임계값 초과 유저가 Redis에 없으면 → Kafka COUPON 이벤트 1회 발행")
    void runBehaviorCouponTrigger_newUser_publishesCouponToKafka() throws Exception {
        Long ruleId = 1L;
        Long userId = 1L;
        Long productId = 100L;
        MarketingRule rule = couponRule(ruleId);

        when(marketingRuleRepository.findByIsActiveTrue()).thenReturn(List.of(rule));
        when(behaviorEventService.getHighlyEngagedUsersForProduct(any(), any(), anyString(), anyInt()))
                .thenReturn(Map.of(userId, List.of(productId)));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(
                eq(String.format("coupon:trigger:%d:%d:%d", ruleId, userId, productId)),
                eq("1"), eq(30L), eq(TimeUnit.DAYS)))
                .thenReturn(true);

        scheduler.runBehaviorCouponTrigger();

        verify(kafkaTemplate, times(1)).send(eq(KafkaTopics.CAMPAIGN_ACTIVITY_COMMAND), any());
    }

    @Test
    @DisplayName("동일 유저·상품 조합이 Redis에 이미 존재하면 → 중복 발급 방지, Kafka 발행 없음")
    void runBehaviorCouponTrigger_alreadyIssued_doesNotPublishToKafka() throws Exception {
        Long ruleId = 1L;
        Long userId = 2L;
        Long productId = 200L;
        MarketingRule rule = couponRule(ruleId);

        when(marketingRuleRepository.findByIsActiveTrue()).thenReturn(List.of(rule));
        when(behaviorEventService.getHighlyEngagedUsersForProduct(any(), any(), anyString(), anyInt()))
                .thenReturn(Map.of(userId, List.of(productId)));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(
                eq(String.format("coupon:trigger:%d:%d:%d", ruleId, userId, productId)),
                eq("1"), eq(30L), eq(TimeUnit.DAYS)))
                .thenReturn(false);

        scheduler.runBehaviorCouponTrigger();

        verify(kafkaTemplate, never()).send(anyString(), any());
    }

    @Test
    @DisplayName("1명의 유저가 여러 상품을 조회했을 때 → 각 상품별로 쿠폰 발행 (2회)")
    void runBehaviorCouponTrigger_multipleProducts_publishesPerProduct() throws Exception {
        Long ruleId = 1L;
        Long userId = 3L;
        Long productId1 = 301L;
        Long productId2 = 302L;
        MarketingRule rule = couponRule(ruleId);

        when(marketingRuleRepository.findByIsActiveTrue()).thenReturn(List.of(rule));
        when(behaviorEventService.getHighlyEngagedUsersForProduct(any(), any(), anyString(), anyInt()))
                .thenReturn(Map.of(userId, List.of(productId1, productId2)));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), eq("1"), eq(30L), eq(TimeUnit.DAYS)))
                .thenReturn(true);

        scheduler.runBehaviorCouponTrigger();

        verify(kafkaTemplate, times(2)).send(eq(KafkaTopics.CAMPAIGN_ACTIVITY_COMMAND), any());
    }
}
