package com.axon.entry_service.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.axon.entry_service.config.auth.JwtTokenProvider;
import com.axon.entry_service.reservation.domain.CampaignActivityMeta;
import com.axon.entry_service.reservation.domain.CampaignActivityStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class CampaignActivityMetaServiceRedisIntegrationTest {
    @Container
    static final GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;
    private CampaignActivityMetaService service;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        redisTemplate = spy(new StringRedisTemplate(connectionFactory));
        try (var connection = connectionFactory.getConnection()) {
            connection.serverCommands().flushAll();
        }
        RestClient.Builder builder = RestClient.builder().baseUrl("http://core");
        server = MockRestServiceServer.bindTo(builder).build();
        JwtTokenProvider jwt = mock(JwtTokenProvider.class);
        when(jwt.generateAccessToken(0L)).thenReturn("system-token");
        service = new CampaignActivityMetaService(builder.build(), redisTemplate,
                new ObjectMapper().findAndRegisterModules(), jwt);
    }

    @AfterEach
    void tearDown() {
        connectionFactory.destroy();
    }

    @Test
    void concurrentColdRequestsShareOneCoreCallAndThenUseRedisCache() throws Exception {
        CountDownLatch fetched = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch cacheMisses = new CountDownLatch(16);
        Set<Thread> readers = ConcurrentHashMap.newKeySet();
        ValueOperations<String, String> values = spy(redisTemplate.opsForValue());
        doReturn(values).when(redisTemplate).opsForValue();
        doAnswer(invocation -> {
            Object value = invocation.callRealMethod();
            if (value == null && readers.add(Thread.currentThread())) {
                cacheMisses.countDown();
            }
            return value;
        }).when(values).get("campaign:1:meta");
        server.expect(requestTo("http://core/api/v1/campaign-activities/1")).andRespond(request -> {
            fetched.countDown();
            await(release);
            return withSuccess(response("ACTIVE"), MediaType.APPLICATION_JSON).createResponse(request);
        });

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<CampaignActivityMeta>> results = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                results.add(executor.submit(() -> service.getMeta(1L)));
            }
            try {
                assertThat(fetched.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(cacheMisses.await(5, TimeUnit.SECONDS)).isTrue();
            } finally {
                release.countDown();
            }
            for (var result : results) {
                assertThat(result.get(10, TimeUnit.SECONDS).status()).isEqualTo(CampaignActivityStatus.ACTIVE);
            }
        }
        assertThat(service.getMeta(1L).status()).isEqualTo(CampaignActivityStatus.ACTIVE);
        assertThat(redisTemplate.getExpire("campaign:1:meta")).isBetween(3500L, 3600L);
        server.verify();
    }

    @Test
    void invalidationDuringFetchPreventsStaleCacheRefill() {
        server.expect(requestTo("http://core/api/v1/campaign-activities/1")).andRespond(request -> {
            service.evictMeta(1L);
            return withSuccess(response("ACTIVE"), MediaType.APPLICATION_JSON).createResponse(request);
        });
        server.expect(requestTo("http://core/api/v1/campaign-activities/1"))
                .andRespond(withSuccess(response("PAUSED"), MediaType.APPLICATION_JSON));

        // An already-running request may finish with the old value, but must not cache it.
        assertThat(service.getMeta(1L).status()).isEqualTo(CampaignActivityStatus.ACTIVE);
        assertThat(redisTemplate.opsForValue().get("campaign:1:meta")).isNull();
        assertThat(redisTemplate.opsForValue().get("campaign:1:meta:version")).isEqualTo("1");
        assertThat(service.getMeta(1L).status()).isEqualTo(CampaignActivityStatus.PAUSED);
        assertThat(service.getMeta(1L).status()).isEqualTo(CampaignActivityStatus.PAUSED);
        server.verify();
    }

    @Test
    void failedFlightDoesNotBlockNextRequest() {
        server.expect(requestTo("http://core/api/v1/campaign-activities/1")).andRespond(withServerError());
        server.expect(requestTo("http://core/api/v1/campaign-activities/1"))
                .andRespond(withSuccess(response("ACTIVE"), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> service.getMeta(1L)).isInstanceOf(IllegalStateException.class);
        assertThat(service.getMeta(1L).status()).isEqualTo(CampaignActivityStatus.ACTIVE);
        server.verify();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for concurrent requests");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static String response(String status) {
        return """
                {"id":1,"campaignId":2,"limitCount":100,"status":"%s",
                 "filters":[],"productId":3,"activityType":"FIRST_COME_FIRST_SERVE"}
                """.formatted(status);
    }
}
