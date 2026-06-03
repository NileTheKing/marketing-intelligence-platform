package com.axon.entry_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.axon.entry_service.domain.CampaignActivityMeta;
import com.axon.entry_service.domain.CampaignActivityStatus;
import com.axon.entry_service.domain.ReservationResult;
import com.axon.entry_service.domain.ReservationStatus;
import com.axon.messaging.CampaignActivityType;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class EntryReservationServiceRedisIntegrationTest {

    private static final long CAMPAIGN_ACTIVITY_ID = 1L;

    @Container
    static final GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;
    private EntryReservationService reservationService;

    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();

        redisTemplate = new StringRedisTemplate();
        redisTemplate.setConnectionFactory(connectionFactory);
        redisTemplate.afterPropertiesSet();

        reservationService = new EntryReservationService(
                redisTemplate,
                mock(ApplicationEventPublisher.class));
    }

    @AfterEach
    void tearDown() {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            connection.serverCommands().flushAll();
        }
        connectionFactory.destroy();
    }

    @Test
    void reserveExecutesLuaScriptOnRedisAndRejectsDuplicateUser() {
        CampaignActivityMeta meta = activeMeta(3);

        ReservationResult first = reservationService.reserve(CAMPAIGN_ACTIVITY_ID, 100L, meta, Instant.now());
        ReservationResult duplicated = reservationService.reserve(CAMPAIGN_ACTIVITY_ID, 100L, meta, Instant.now());

        assertThat(first.status()).isEqualTo(ReservationStatus.SUCCESS);
        assertThat(first.order()).isEqualTo(1L);
        assertThat(duplicated.status()).isEqualTo(ReservationStatus.DUPLICATED);
        assertThat(redisTemplate.opsForSet().size(participantsKey())).isEqualTo(1L);
        assertThat(redisTemplate.opsForValue().get(counterKey())).isEqualTo("1");
    }

    @Test
    void reserveRollsBackRedisStateWhenLimitExceeded() {
        CampaignActivityMeta meta = activeMeta(1);

        ReservationResult first = reservationService.reserve(CAMPAIGN_ACTIVITY_ID, 100L, meta, Instant.now());
        ReservationResult soldOut = reservationService.reserve(CAMPAIGN_ACTIVITY_ID, 101L, meta, Instant.now());

        assertThat(first.status()).isEqualTo(ReservationStatus.SUCCESS);
        assertThat(soldOut.status()).isEqualTo(ReservationStatus.SOLD_OUT);
        assertThat(redisTemplate.opsForSet().size(participantsKey())).isEqualTo(1L);
        assertThat(redisTemplate.opsForValue().get(counterKey())).isEqualTo("1");
        assertThat(redisTemplate.opsForSet().isMember(participantsKey(), "101")).isFalse();
    }

    @Test
    void reserveKeepsCounterAndParticipantsConsistentUnderConcurrentRequests() throws Exception {
        int limit = 20;
        int requestCount = 100;
        CampaignActivityMeta meta = activeMeta(limit);
        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(requestCount);

        try {
            List<Callable<ReservationStatus>> tasks = java.util.stream.IntStream.range(0, requestCount)
                    .mapToObj(index -> (Callable<ReservationStatus>) () -> {
                        ready.countDown();
                        start.await();
                        return reservationService.reserve(
                                CAMPAIGN_ACTIVITY_ID,
                                1_000L + index,
                                meta,
                                Instant.now()).status();
                    })
                    .toList();

            List<java.util.concurrent.Future<ReservationStatus>> futures = tasks.stream()
                    .map(executor::submit)
                    .toList();

            ready.await();
            start.countDown();

            List<ReservationStatus> results = futures.stream()
                    .map(future -> {
                        try {
                            return future.get();
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .toList();

            assertThat(results).filteredOn(status -> status == ReservationStatus.SUCCESS).hasSize(limit);
            assertThat(results).filteredOn(status -> status == ReservationStatus.SOLD_OUT).hasSize(requestCount - limit);
            assertThat(redisTemplate.opsForSet().size(participantsKey())).isEqualTo((long) limit);
            assertThat(redisTemplate.opsForValue().get(counterKey())).isEqualTo(String.valueOf(limit));
        } finally {
            executor.shutdownNow();
        }
    }

    private CampaignActivityMeta activeMeta(int limitCount) {
        return new CampaignActivityMeta(
                CAMPAIGN_ACTIVITY_ID,
                1L,
                limitCount,
                CampaignActivityStatus.ACTIVE,
                null,
                null,
                Collections.emptyList(),
                false,
                false,
                10L,
                null,
                CampaignActivityType.FIRST_COME_FIRST_SERVE);
    }

    private String participantsKey() {
        return "campaign:%d:users".formatted(CAMPAIGN_ACTIVITY_ID);
    }

    private String counterKey() {
        return "campaign:%d:counter".formatted(CAMPAIGN_ACTIVITY_ID);
    }
}
