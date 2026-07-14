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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BehaviorTriggerSchedulerTest {

    @Mock
    private BehaviorEventService behaviorEventService;

    @Mock
    private MarketingRuleRepository marketingRuleRepository;

    @Mock
    private MarketingActionRepository marketingActionRepository;

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    @InjectMocks
    private BehaviorTriggerScheduler scheduler;

    private MarketingRule marketingRule(Long ruleId, Long targetProductId, Integer dedupTtlDays) {
        MarketingRule rule = MarketingRule.builder()
                .ruleName("test-rule")
                .behaviorType("PAGE_VIEW")
                .targetProductId(targetProductId)
                .thresholdCount(3)
                .lookbackDays(7)
                .dedupTtlDays(dedupTtlDays)
                .isActive(true)
                .build();
        setId(rule, ruleId);
        return rule;
    }

    private MarketingRule marketingRule(Long ruleId) {
        return marketingRule(ruleId, null, null);
    }

    private MarketingAction marketingAction(Long actionId, MarketingRule rule, RewardType actionType,
                                             Long referenceId, boolean isActive) {
        MarketingAction action = MarketingAction.builder()
                .marketingRule(rule)
                .actionType(actionType)
                .referenceId(referenceId)
                .isActive(isActive)
                .build();
        setId(action, actionId);
        return action;
    }

    private void setId(Object entity, Long id) {
        try {
            var f = entity.getClass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void stubSuccessfulSend() {
        when(kafkaTemplate.send(eq(KafkaTopics.CAMPAIGN_ACTIVITY_COMMAND), any()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
    }

    @Test
    @DisplayName("룰별 중복 방지 TTL이 있으면 Redis setIfAbsent에 해당 TTL을 사용한다")
    void runBehaviorCouponTrigger_ruleDedupTtl_usesRuleValue() throws Exception {
        Long ruleId = 5L, actionId = 50L, userId = 7L, productId = 700L;
        MarketingRule rule = marketingRule(ruleId, null, 14);
        MarketingAction action = marketingAction(actionId, rule, RewardType.COUPON, 999L, true);

        when(marketingRuleRepository.findByIsActiveTrue()).thenReturn(List.of(rule));
        when(marketingActionRepository.findByMarketingRuleIdInAndIsActiveTrue(List.of(ruleId)))
                .thenReturn(List.of(action));
        when(behaviorEventService.getHighlyEngagedUsersForProduct(any(), any(), anyString(), anyInt(), isNull(), isNull()))
                .thenReturn(Map.of(userId, List.of(productId)));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(
                eq(String.format("marketing:action-trigger:%d:%d:%d", actionId, userId, productId)),
                eq("1"), eq(14L), eq(TimeUnit.DAYS)))
                .thenReturn(true);
        stubSuccessfulSend();

        scheduler.runBehaviorCouponTrigger();

        verify(valueOperations).setIfAbsent(anyString(), eq("1"), eq(14L), eq(TimeUnit.DAYS));
        verify(kafkaTemplate).send(eq(KafkaTopics.CAMPAIGN_ACTIVITY_COMMAND), any());
    }

    @Test
    @DisplayName("조회 임계값 초과 유저가 Redis에 없으면 → Kafka COUPON 이벤트 1회 발행")
    void runBehaviorCouponTrigger_newUser_publishesCouponToKafka() throws Exception {
        Long ruleId = 1L, actionId = 10L, userId = 1L, productId = 100L;
        MarketingRule rule = marketingRule(ruleId);
        MarketingAction action = marketingAction(actionId, rule, RewardType.COUPON, 999L, true);

        when(marketingRuleRepository.findByIsActiveTrue()).thenReturn(List.of(rule));
        when(marketingActionRepository.findByMarketingRuleIdInAndIsActiveTrue(List.of(ruleId)))
                .thenReturn(List.of(action));
        when(behaviorEventService.getHighlyEngagedUsersForProduct(any(), any(), anyString(), anyInt(), isNull(), isNull()))
                .thenReturn(Map.of(userId, List.of(productId)));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), eq("1"), eq(30L), eq(TimeUnit.DAYS)))
                .thenReturn(true);
        stubSuccessfulSend();

        scheduler.runBehaviorCouponTrigger();

        verify(kafkaTemplate, times(1)).send(eq(KafkaTopics.CAMPAIGN_ACTIVITY_COMMAND), any());
    }

    @Test
    @DisplayName("동일 유저·상품 조합이 Redis에 이미 존재하면 → 중복 발급 방지, Kafka 발행 없음")
    void runBehaviorCouponTrigger_alreadyIssued_doesNotPublishToKafka() throws Exception {
        Long ruleId = 1L, actionId = 10L, userId = 2L, productId = 200L;
        MarketingRule rule = marketingRule(ruleId);
        MarketingAction action = marketingAction(actionId, rule, RewardType.COUPON, 999L, true);

        when(marketingRuleRepository.findByIsActiveTrue()).thenReturn(List.of(rule));
        when(marketingActionRepository.findByMarketingRuleIdInAndIsActiveTrue(List.of(ruleId)))
                .thenReturn(List.of(action));
        when(behaviorEventService.getHighlyEngagedUsersForProduct(any(), any(), anyString(), anyInt(), isNull(), isNull()))
                .thenReturn(Map.of(userId, List.of(productId)));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), eq("1"), eq(30L), eq(TimeUnit.DAYS)))
                .thenReturn(false);

        scheduler.runBehaviorCouponTrigger();

        verify(kafkaTemplate, never()).send(anyString(), any());
    }

    @Test
    @DisplayName("1명의 유저가 여러 상품을 조회했을 때 → 각 상품별로 쿠폰 발행 (2회)")
    void runBehaviorCouponTrigger_multipleProducts_publishesPerProduct() throws Exception {
        Long ruleId = 1L, actionId = 10L, userId = 3L, productId1 = 301L, productId2 = 302L;
        MarketingRule rule = marketingRule(ruleId);
        MarketingAction action = marketingAction(actionId, rule, RewardType.COUPON, 999L, true);

        when(marketingRuleRepository.findByIsActiveTrue()).thenReturn(List.of(rule));
        when(marketingActionRepository.findByMarketingRuleIdInAndIsActiveTrue(List.of(ruleId)))
                .thenReturn(List.of(action));
        when(behaviorEventService.getHighlyEngagedUsersForProduct(any(), any(), anyString(), anyInt(), isNull(), isNull()))
                .thenReturn(Map.of(userId, List.of(productId1, productId2)));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), eq("1"), eq(30L), eq(TimeUnit.DAYS)))
                .thenReturn(true);
        stubSuccessfulSend();

        scheduler.runBehaviorCouponTrigger();

        verify(kafkaTemplate, times(2)).send(eq(KafkaTopics.CAMPAIGN_ACTIVITY_COMMAND), any());
    }

    @Test
    @DisplayName("Webhook 액션도 Kafka WEBHOOK 이벤트로 발행해야 한다")
    void runBehaviorCouponTrigger_webhookAction_publishesWebhookToKafka() throws Exception {
        Long ruleId = 2L, actionId = 20L, userId = 4L, productId = 400L;
        MarketingRule rule = marketingRule(ruleId);
        MarketingAction action = marketingAction(actionId, rule, RewardType.WEBHOOK, 999L, true);

        when(marketingRuleRepository.findByIsActiveTrue()).thenReturn(List.of(rule));
        when(marketingActionRepository.findByMarketingRuleIdInAndIsActiveTrue(List.of(ruleId)))
                .thenReturn(List.of(action));
        when(behaviorEventService.getHighlyEngagedUsersForProduct(any(), any(), anyString(), anyInt(), isNull(), isNull()))
                .thenReturn(Map.of(userId, List.of(productId)));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), eq("1"), eq(30L), eq(TimeUnit.DAYS)))
                .thenReturn(true);
        stubSuccessfulSend();

        scheduler.runBehaviorCouponTrigger();

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(eq(KafkaTopics.CAMPAIGN_ACTIVITY_COMMAND), captor.capture());

        CampaignActivityKafkaProducerDto message = (CampaignActivityKafkaProducerDto) captor.getValue();
        assertThat(message.getCampaignActivityType()).isEqualTo(CampaignActivityType.WEBHOOK);
        assertThat(message.getMarketingRuleId()).isEqualTo(ruleId);
        assertThat(message.getMarketingActionId()).isEqualTo(actionId);
        assertThat(message.getActionReferenceId()).isEqualTo(999L);
    }

    @Test
    @DisplayName("targetProductId가 있으면 해당 상품 기준으로만 행동 조건을 평가한다")
    void runBehaviorCouponTrigger_targetProductId_passesProductFilterToBehaviorQuery() throws Exception {
        Long ruleId = 3L, actionId = 30L, userId = 5L, targetProductId = 555L;
        MarketingRule rule = marketingRule(ruleId, targetProductId, null);
        MarketingAction action = marketingAction(actionId, rule, RewardType.COUPON, 999L, true);

        when(marketingRuleRepository.findByIsActiveTrue()).thenReturn(List.of(rule));
        when(marketingActionRepository.findByMarketingRuleIdInAndIsActiveTrue(List.of(ruleId)))
                .thenReturn(List.of(action));
        when(behaviorEventService.getHighlyEngagedUsersForProduct(any(), any(), anyString(), anyInt(), eq(targetProductId), isNull()))
                .thenReturn(Map.of(userId, List.of(targetProductId)));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), eq("1"), eq(30L), eq(TimeUnit.DAYS)))
                .thenReturn(true);
        stubSuccessfulSend();

        scheduler.runBehaviorCouponTrigger();

        verify(behaviorEventService).getHighlyEngagedUsersForProduct(any(), any(), eq("PAGE_VIEW"), eq(3), eq(targetProductId), isNull());
        verify(kafkaTemplate).send(eq(KafkaTopics.CAMPAIGN_ACTIVITY_COMMAND), any());
    }

    @Test
    @DisplayName("SCROLL + propertyConditions(depth:75) 룰 → depth 조건이 ES 쿼리에 전달된다")
    void runBehaviorCouponTrigger_scrollRuleWithDepthCondition_passesPropertyConditionsToBehaviorQuery() throws Exception {
        Long ruleId = 4L, actionId = 40L, userId = 6L, productId = 600L;
        Map<String, Object> depthCondition = Map.of("depth", 75);

        MarketingRule rule = MarketingRule.builder()
                .ruleName("scroll-depth-rule")
                .behaviorType("SCROLL")
                .thresholdCount(1)
                .lookbackDays(7)
                .isActive(true)
                .propertyConditions(depthCondition)
                .build();
        setId(rule, ruleId);
        MarketingAction action = marketingAction(actionId, rule, RewardType.COUPON, 888L, true);

        when(marketingRuleRepository.findByIsActiveTrue()).thenReturn(List.of(rule));
        when(marketingActionRepository.findByMarketingRuleIdInAndIsActiveTrue(List.of(ruleId)))
                .thenReturn(List.of(action));
        when(behaviorEventService.getHighlyEngagedUsersForProduct(
                any(), any(), eq("SCROLL"), eq(1), isNull(), eq(depthCondition)))
                .thenReturn(Map.of(userId, List.of(productId)));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), eq("1"), eq(30L), eq(TimeUnit.DAYS)))
                .thenReturn(true);
        stubSuccessfulSend();

        scheduler.runBehaviorCouponTrigger();

        verify(behaviorEventService).getHighlyEngagedUsersForProduct(
                any(), any(), eq("SCROLL"), eq(1), isNull(), eq(depthCondition));
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(eq(KafkaTopics.CAMPAIGN_ACTIVITY_COMMAND), captor.capture());
        CampaignActivityKafkaProducerDto message = (CampaignActivityKafkaProducerDto) captor.getValue();
        assertThat(message.getCampaignActivityType()).isEqualTo(CampaignActivityType.COUPON);
    }

    @Test
    @DisplayName("쿠폰+웹훅 액션이 모두 있으면 서로 다른 action/reference ID로 각각 발행한다")
    void runBehaviorCouponTrigger_couponAndWebhookActions_publishesTwoDistinctCommands() throws Exception {
        Long ruleId = 6L, couponActionId = 61L, webhookActionId = 62L, userId = 8L, productId = 800L;
        MarketingRule rule = marketingRule(ruleId);
        MarketingAction couponAction = marketingAction(couponActionId, rule, RewardType.COUPON, 111L, true);
        MarketingAction webhookAction = marketingAction(webhookActionId, rule, RewardType.WEBHOOK, 222L, true);

        when(marketingRuleRepository.findByIsActiveTrue()).thenReturn(List.of(rule));
        when(marketingActionRepository.findByMarketingRuleIdInAndIsActiveTrue(List.of(ruleId)))
                .thenReturn(List.of(couponAction, webhookAction));
        when(behaviorEventService.getHighlyEngagedUsersForProduct(any(), any(), anyString(), anyInt(), isNull(), isNull()))
                .thenReturn(Map.of(userId, List.of(productId)));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), eq("1"), eq(30L), eq(TimeUnit.DAYS)))
                .thenReturn(true);
        stubSuccessfulSend();

        scheduler.runBehaviorCouponTrigger();

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate, times(2)).send(eq(KafkaTopics.CAMPAIGN_ACTIVITY_COMMAND), captor.capture());

        List<CampaignActivityKafkaProducerDto> messages = captor.getAllValues().stream()
                .map(CampaignActivityKafkaProducerDto.class::cast)
                .toList();
        assertThat(messages).extracting(CampaignActivityKafkaProducerDto::getMarketingActionId)
                .containsExactlyInAnyOrder(couponActionId, webhookActionId);
        assertThat(messages).extracting(CampaignActivityKafkaProducerDto::getActionReferenceId)
                .containsExactlyInAnyOrder(111L, 222L);
        assertThat(messages).extracting(CampaignActivityKafkaProducerDto::getCampaignActivityType)
                .containsExactlyInAnyOrder(CampaignActivityType.COUPON, CampaignActivityType.WEBHOOK);
    }

    @Test
    @DisplayName("비활성 액션은 건너뛰고, 활성 액션만 발행한다")
    void runBehaviorCouponTrigger_inactiveAction_isSkippedButActiveActionPublishes() throws Exception {
        Long ruleId = 7L, activeActionId = 71L, inactiveActionId = 72L, userId = 9L, productId = 900L;
        MarketingRule rule = marketingRule(ruleId);
        MarketingAction activeAction = marketingAction(activeActionId, rule, RewardType.COUPON, 111L, true);

        when(marketingRuleRepository.findByIsActiveTrue()).thenReturn(List.of(rule));
        // 리포지토리 쿼리 자체가 isActive=true만 반환하므로 비활성 액션은 결과에 없다
        when(marketingActionRepository.findByMarketingRuleIdInAndIsActiveTrue(List.of(ruleId)))
                .thenReturn(List.of(activeAction));
        when(behaviorEventService.getHighlyEngagedUsersForProduct(any(), any(), anyString(), anyInt(), isNull(), isNull()))
                .thenReturn(Map.of(userId, List.of(productId)));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), eq("1"), eq(30L), eq(TimeUnit.DAYS)))
                .thenReturn(true);
        stubSuccessfulSend();

        scheduler.runBehaviorCouponTrigger();

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate, times(1)).send(eq(KafkaTopics.CAMPAIGN_ACTIVITY_COMMAND), captor.capture());
        CampaignActivityKafkaProducerDto message = (CampaignActivityKafkaProducerDto) captor.getValue();
        assertThat(message.getMarketingActionId()).isEqualTo(activeActionId);
    }

    @Test
    @DisplayName("action row가 없는 룰은 아무것도 발행하지 않는다")
    void runBehaviorCouponTrigger_ruleWithNoActiveActions_publishesNothing() throws Exception {
        Long ruleId = 8L;
        MarketingRule rule = marketingRule(ruleId);

        when(marketingRuleRepository.findByIsActiveTrue()).thenReturn(List.of(rule));
        when(marketingActionRepository.findByMarketingRuleIdInAndIsActiveTrue(List.of(ruleId)))
                .thenReturn(List.of());

        scheduler.runBehaviorCouponTrigger();

        verifyNoInteractions(behaviorEventService);
        verify(kafkaTemplate, never()).send(anyString(), any());
    }

    @Test
    @DisplayName("Kafka 발행이 실패하면 해당 액션의 dedup 키만 삭제한다")
    void runBehaviorCouponTrigger_kafkaSendFails_deletesOnlyFailedActionKey() throws Exception {
        Long ruleId = 9L, actionId = 90L, userId = 10L, productId = 1000L;
        MarketingRule rule = marketingRule(ruleId);
        MarketingAction action = marketingAction(actionId, rule, RewardType.COUPON, 999L, true);
        String expectedKey = String.format("marketing:action-trigger:%d:%d:%d", actionId, userId, productId);

        when(marketingRuleRepository.findByIsActiveTrue()).thenReturn(List.of(rule));
        when(marketingActionRepository.findByMarketingRuleIdInAndIsActiveTrue(List.of(ruleId)))
                .thenReturn(List.of(action));
        when(behaviorEventService.getHighlyEngagedUsersForProduct(any(), any(), anyString(), anyInt(), isNull(), isNull()))
                .thenReturn(Map.of(userId, List.of(productId)));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq(expectedKey), eq("1"), eq(30L), eq(TimeUnit.DAYS)))
                .thenReturn(true);

        CompletableFuture<SendResult<String, Object>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("kafka unavailable"));
        when(kafkaTemplate.send(eq(KafkaTopics.CAMPAIGN_ACTIVITY_COMMAND), any())).thenReturn(failedFuture);

        scheduler.runBehaviorCouponTrigger();

        verify(redisTemplate).delete(expectedKey);
    }

    @Test
    @DisplayName("Kafka send()가 동기적으로 예외를 던져도 해당 액션의 dedup 키를 삭제한다")
    void runBehaviorCouponTrigger_kafkaSendThrowsSynchronously_deletesActionKey() throws Exception {
        Long ruleId = 10L, actionId = 100L, userId = 11L, productId = 1100L;
        MarketingRule rule = marketingRule(ruleId);
        MarketingAction action = marketingAction(actionId, rule, RewardType.COUPON, 999L, true);
        String expectedKey = String.format("marketing:action-trigger:%d:%d:%d", actionId, userId, productId);

        when(marketingRuleRepository.findByIsActiveTrue()).thenReturn(List.of(rule));
        when(marketingActionRepository.findByMarketingRuleIdInAndIsActiveTrue(List.of(ruleId)))
                .thenReturn(List.of(action));
        when(behaviorEventService.getHighlyEngagedUsersForProduct(any(), any(), anyString(), anyInt(), isNull(), isNull()))
                .thenReturn(Map.of(userId, List.of(productId)));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq(expectedKey), eq("1"), eq(30L), eq(TimeUnit.DAYS)))
                .thenReturn(true);
        when(kafkaTemplate.send(eq(KafkaTopics.CAMPAIGN_ACTIVITY_COMMAND), any()))
                .thenThrow(new org.apache.kafka.common.errors.SerializationException("boom"));

        scheduler.runBehaviorCouponTrigger();

        verify(redisTemplate).delete(expectedKey);
    }
}
