package com.axon.entry_service.service.payment;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

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
}
