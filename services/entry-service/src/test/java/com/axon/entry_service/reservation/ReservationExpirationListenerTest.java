package com.axon.entry_service.reservation;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

class ReservationExpirationListenerTest {
    @Test
    void v2CredentialExpirationDoesNotReleaseLegacyStock() {
        var redis = mock(StringRedisTemplate.class);
        var listener = new ReservationExpirationListener(mock(RedisMessageListenerContainer.class), redis);
        listener.onMessage(new DefaultMessage("__keyevent@0__:expired".getBytes(StandardCharsets.UTF_8),
                "axon:v2:credential:v2p.example".getBytes(StandardCharsets.UTF_8)), null);
        verifyNoInteractions(redis);
    }
}
