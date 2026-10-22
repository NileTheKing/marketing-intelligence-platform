package com.axon.entry_service.service.payment;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReservationTokenServiceTest {

    private final ReservationTokenService tokenService = new ReservationTokenService(
            mock(RedisTemplate.class), "test-payment-token-secret-that-is-long-enough");

    @Test
    void deterministicTokenHasValidSignature() {
        String token = tokenService.generateDeterministicToken(10L, 20L);

        Boolean valid = ReflectionTestUtils.invokeMethod(tokenService, "verifyTokenSignature", token);

        assertThat(valid).isTrue();
    }

    @Test
    void changedTokenDoesNotHaveValidSignature() {
        String token = tokenService.generateDeterministicToken(10L, 20L);
        char replacement = token.charAt(0) == 'A' ? 'B' : 'A';
        String changed = replacement + token.substring(1);

        Boolean valid = ReflectionTestUtils.invokeMethod(tokenService, "verifyTokenSignature", changed);

        assertThat(valid).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void confirmationLeasePassesTtlToLuaAsPlainString() {
        RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
        ReservationTokenService service = new ReservationTokenService(
                redisTemplate, "test-payment-token-secret-that-is-long-enough");
        when(redisTemplate.execute(
                any(RedisScript.class), any(RedisSerializer.class), isNull(), anyList(), any()))
                .thenReturn(2L);

        assertThat(service.tryAcquireConfirmationLease("primary-token"))
                .isEqualTo(ReservationTokenService.ConfirmationLeaseResult.ACQUIRED);

        ArgumentCaptor<RedisSerializer<?>> serializer = ArgumentCaptor.forClass(RedisSerializer.class);
        ArgumentCaptor<Object> ttl = ArgumentCaptor.forClass(Object.class);
        verify(redisTemplate).execute(any(RedisScript.class), serializer.capture(), isNull(), anyList(), ttl.capture());
        RedisSerializer<Object> stringSerializer = (RedisSerializer<Object>) serializer.getValue();
        assertThat(new String(stringSerializer.serialize(ttl.getValue()), StandardCharsets.UTF_8))
                .isEqualTo("30");
    }
}
