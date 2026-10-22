package com.axon.core_service.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.axon.messaging.dto.UserBehaviorEventMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.JacksonUtils;

class UserBehaviorEventMessageSerializationTest {

    // Same mapper Spring Kafka's JsonSerializer/JsonDeserializer use by default.
    private final ObjectMapper mapper = JacksonUtils.enhancedObjectMapper();

    @Test
    @DisplayName("occurredAt은 Elasticsearch date로 파싱되는 ISO-8601 문자열로 직렬화된다")
    void occurredAtIsSerializedAsIsoText() throws Exception {
        UserBehaviorEventMessage message = UserBehaviorEventMessage.builder()
                .eventName("PAGE_VIEW")
                .triggerType("PAGE_VIEW")
                .occurredAt(Instant.parse("2026-10-19T04:29:29.123Z"))
                .build();

        JsonNode json = mapper.readTree(mapper.writeValueAsString(message));

        assertThat(json.get("occurredAt").isTextual()).isTrue();
        assertThat(json.get("occurredAt").asText()).isEqualTo("2026-10-19T04:29:29.123Z");
    }

    @Test
    @DisplayName("이미 토픽에 있는 숫자형 occurredAt 메시지도 그대로 읽는다")
    void legacyDecimalSecondsStillDeserialize() throws Exception {
        UserBehaviorEventMessage legacy = mapper.readValue(
                "{\"triggerType\":\"PAGE_VIEW\",\"occurredAt\":1792384169.123}", UserBehaviorEventMessage.class);
        UserBehaviorEventMessage iso = mapper.readValue(
                "{\"triggerType\":\"PAGE_VIEW\",\"occurredAt\":\"2026-10-19T04:29:29.123Z\"}", UserBehaviorEventMessage.class);

        assertThat(legacy.getOccurredAt()).isEqualTo(Instant.parse("2026-10-19T04:29:29.123Z"));
        assertThat(iso.getOccurredAt()).isEqualTo(Instant.parse("2026-10-19T04:29:29.123Z"));
    }
}
