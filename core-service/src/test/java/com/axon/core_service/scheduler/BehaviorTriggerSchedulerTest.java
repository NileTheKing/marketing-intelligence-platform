package com.axon.core_service.scheduler;

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

/**
 * 행동 기반 쿠폰 트리거 스케줄러 단위 테스트
 *
 * 핵심 시나리오:
 * 1. ES에서 추출한 고관여 유저가 Redis에 미등록 상태 → Kafka COUPON 이벤트 발행
 * 2. 동일 유저·상품 조합이 Redis에 이미 존재 → 중복 발급 방지 (Kafka 발행 없음)
 */
@ExtendWith(MockitoExtension.class)
class BehaviorTriggerSchedulerTest {

    @Mock
    private BehaviorEventService behaviorEventService;

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    @InjectMocks
    private BehaviorTriggerScheduler scheduler;

    @Test
    @DisplayName("조회 임계값 초과 유저가 Redis에 없으면 → Kafka COUPON 이벤트 1회 발행")
    void runBehaviorCouponTrigger_newUser_publishesCouponToKafka() throws Exception {
        // Given: ES에서 userId=1, productId=100 조합이 추출됨
        Long userId = 1L;
        Long productId = 100L;
        Map<Long, List<Long>> engagedUsers = Map.of(userId, List.of(productId));

        when(behaviorEventService.getHighlyEngagedUsersForProduct(any(), any(), anyString(), anyInt()))
                .thenReturn(engagedUsers);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        // Redis에 해당 키가 없음 → setIfAbsent 성공 (true)
        when(valueOperations.setIfAbsent(
                eq("coupon:trigger:" + userId + ":" + productId), eq("1"), eq(30L), eq(TimeUnit.DAYS)))
                .thenReturn(true);

        // When
        scheduler.runBehaviorCouponTrigger();

        // Then: Kafka에 COUPON 이벤트가 정확히 1회 발행되어야 한다
        verify(kafkaTemplate, times(1)).send(eq(KafkaTopics.CAMPAIGN_ACTIVITY_COMMAND), any());
    }

    @Test
    @DisplayName("동일 유저·상품 조합이 Redis에 이미 존재하면 → 중복 발급 방지, Kafka 발행 없음")
    void runBehaviorCouponTrigger_alreadyIssued_doesNotPublishToKafka() throws Exception {
        // Given: 동일 유저·상품 조합이 조회됨
        Long userId = 2L;
        Long productId = 200L;
        Map<Long, List<Long>> engagedUsers = Map.of(userId, List.of(productId));

        when(behaviorEventService.getHighlyEngagedUsersForProduct(any(), any(), anyString(), anyInt()))
                .thenReturn(engagedUsers);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        // Redis에 이미 키가 존재 → setIfAbsent 실패 (false)
        when(valueOperations.setIfAbsent(
                eq("coupon:trigger:" + userId + ":" + productId), eq("1"), eq(30L), eq(TimeUnit.DAYS)))
                .thenReturn(false);

        // When
        scheduler.runBehaviorCouponTrigger();

        // Then: Kafka 발행이 0회여야 한다 (중복 방지)
        verify(kafkaTemplate, never()).send(anyString(), any());
    }

    @Test
    @DisplayName("1명의 유저가 여러 상품을 조회했을 때 → 각 상품별로 쿠폰 발행 (2회)")
    void runBehaviorCouponTrigger_multipleProducts_publishesPerProduct() throws Exception {
        // Given: 동일 유저가 2개 상품 모두 미등록 상태
        Long userId = 3L;
        Long productId1 = 301L;
        Long productId2 = 302L;
        Map<Long, List<Long>> engagedUsers = Map.of(userId, List.of(productId1, productId2));

        when(behaviorEventService.getHighlyEngagedUsersForProduct(any(), any(), anyString(), anyInt()))
                .thenReturn(engagedUsers);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        // 두 상품 모두 Redis 미등록
        when(valueOperations.setIfAbsent(anyString(), eq("1"), eq(30L), eq(TimeUnit.DAYS)))
                .thenReturn(true);

        // When
        scheduler.runBehaviorCouponTrigger();

        // Then: 상품 수만큼 Kafka 발행 (2회)
        verify(kafkaTemplate, times(2)).send(eq(KafkaTopics.CAMPAIGN_ACTIVITY_COMMAND), any());
    }
}
