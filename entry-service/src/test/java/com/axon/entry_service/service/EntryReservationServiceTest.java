package com.axon.entry_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.axon.entry_service.domain.CampaignActivityMeta;
import com.axon.entry_service.domain.CampaignActivityStatus;
import com.axon.entry_service.domain.ReservationResult;
import com.axon.entry_service.domain.ReservationStatus;
import java.time.Instant;
import java.util.List;
import java.util.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

@org.junit.jupiter.api.extension.ExtendWith(MockitoExtension.class)
class EntryReservationServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private EntryReservationService reservationService;

    private CampaignActivityMeta activeMeta;

    @BeforeEach
    void setUp() {
        activeMeta = new CampaignActivityMeta(1L, 1L, 3, CampaignActivityStatus.ACTIVE, null, null, java.util.Collections.emptyList(), false, false, 10L, null, com.axon.messaging.CampaignActivityType.FIRST_COME_FIRST_SERVE);
    }

    @Test
    void reserveSuccess() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any())).thenReturn(1L);

        ReservationResult result = reservationService.reserve(1L, 100L, activeMeta, Instant.now());

        assertThat(result.status()).isEqualTo(ReservationStatus.SUCCESS);
        assertThat(result.order()).isEqualTo(1L);
    }

    @Test
    void reserveDuplicatedWhenUserAlreadyExists() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any())).thenReturn(-1L);

        ReservationResult result = reservationService.reserve(1L, 100L, activeMeta, Instant.now());

        assertThat(result.status()).isEqualTo(ReservationStatus.DUPLICATED);
    }

    @Test
    void reserveSoldOutWhenLimitExceeded() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any())).thenReturn(-2L);

        ReservationResult result = reservationService.reserve(1L, 100L, activeMeta, Instant.now());

        assertThat(result.status()).isEqualTo(ReservationStatus.SOLD_OUT);
    }

    @Test
    void rollbackReservationCallsExecute() {
        reservationService.rollbackReservation(1L, 100L);

        verify(redisTemplate).execute(any(RedisScript.class), anyList(), eq(String.valueOf(100L)));
    }

    @Test
    void reserveClosedWhenActivityInactive() {
        CampaignActivityMeta meta = new CampaignActivityMeta(1L, 1L, 3, CampaignActivityStatus.PAUSED, null, null, java.util.Collections.emptyList(), false, false, 10L, null, com.axon.messaging.CampaignActivityType.FIRST_COME_FIRST_SERVE);

        ReservationResult result = reservationService.reserve(1L, 100L, meta, Instant.now());

        assertThat(result.status()).isEqualTo(ReservationStatus.CLOSED);
    }

    @Test
    void reserveClosedWhenOutsideSchedule() {
        CampaignActivityMeta meta = new CampaignActivityMeta(
                1L,
                1L,
                3,
                CampaignActivityStatus.ACTIVE,
                Instant.now().plusSeconds(3600).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime(),
                null,
                java.util.Collections.emptyList(),
                false,
                false,
                10L,
                null,
                com.axon.messaging.CampaignActivityType.FIRST_COME_FIRST_SERVE
        );

        ReservationResult result = reservationService.reserve(1L, 100L, meta, Instant.now());

        assertThat(result.status()).isEqualTo(ReservationStatus.CLOSED);
    }

    @Test
    void reserveErrorWhenMetaNull() {
        ReservationResult result = reservationService.reserve(1L, 100L, null, Instant.now());

        assertThat(result.status()).isEqualTo(ReservationStatus.ERROR);
    }
}
