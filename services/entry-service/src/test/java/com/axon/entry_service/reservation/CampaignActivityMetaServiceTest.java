package com.axon.entry_service.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.axon.entry_service.config.auth.JwtTokenProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class CampaignActivityMetaServiceTest {
    @Test
    void legacyCouponCacheIsReloadedFromCoreAndModeIsCached() {
        var redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("campaign:1:meta")).thenReturn("""
                {"id":1,"campaignId":2,"status":"ACTIVE","campaignActivityType":"COUPON"}
                """);
        var builder = RestClient.builder().baseUrl("http://core");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://core/api/v1/campaign-activities/1"))
                .andRespond(withSuccess("""
                        {"id":1,"campaignId":2,"status":"ACTIVE","activityType":"COUPON",
                         "couponDeliveryMode":"DIRECT"}
                        """, MediaType.APPLICATION_JSON));
        var jwt = mock(JwtTokenProvider.class);
        when(jwt.generateAccessToken(0L)).thenReturn("token");
        var service = new CampaignActivityMetaService(builder.build(), redis,
                new ObjectMapper().findAndRegisterModules(), jwt);

        assertThat(service.getMeta(1L).couponDeliveryMode()).isEqualTo("DIRECT");
        var payload = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(redis).execute(any(), anyList(), any(), payload.capture(), any());
        assertThat(payload.getValue()).contains("\"couponDeliveryMode\":\"DIRECT\"");
        server.verify();
    }
}
