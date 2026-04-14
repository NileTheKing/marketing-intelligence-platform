package com.axon.core_service.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RealtimeMetricsService TDD 기반 단위 테스트")
public class RealtimeMetricsServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private RealtimeMetricsService realtimeMetricsService;

    @Test
    @DisplayName("정상 케이스: Redis에서 참여자 수 조회")
    void getParticipantCount_Success() {
        // given
        Long activityId = 1L;
        String key = "campaign:1:counter";
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(key)).thenReturn("150");

        // when
        Long count = realtimeMetricsService.getParticipantCount(activityId);

        // then
        assertThat(count).isEqualTo(150L);
    }

    @Test
    @DisplayName("엣지 케이스: Redis에 데이터가 없을 때 0 반환")
    void getParticipantCount_ReturnsZeroWhenNull() {
        // given
        Long activityId = 2L;
        String key = "campaign:2:counter";
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(key)).thenReturn(null);

        // when
        Long count = realtimeMetricsService.getParticipantCount(activityId);

        // then
        assertThat(count).isZero();
    }

    @Test
    @DisplayName("정상 케이스: 남은 재고 계산 (참여자 수 < 총 재고)")
    void getRemainingStock_Positive() {
        // given
        Long participantCount = 100L;
        Long totalStock = 500L;

        // when
        Long remaining = realtimeMetricsService.getRemainingStock(participantCount, totalStock);

        // then
        assertThat(remaining).isEqualTo(400L);
    }

    @Test
    @DisplayName("엣지 케이스: 참여자 수가 총 재고를 넘었을 때 0 반환 (오버부킹 상황 시뮬레이션)")
    void getRemainingStock_ZeroWhenOverbooked() {
        // given
        Long participantCount = 1000L;
        Long totalStock = 500L;

        // when
        Long remaining = realtimeMetricsService.getRemainingStock(participantCount, totalStock);

        // then
        assertThat(remaining).isZero();
    }
}
