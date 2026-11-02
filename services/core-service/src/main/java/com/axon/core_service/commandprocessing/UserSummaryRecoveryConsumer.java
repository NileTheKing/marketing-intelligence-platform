package com.axon.core_service.commandprocessing;

import com.axon.core_service.service.UserSummaryService;
import com.axon.core_service.service.reconciliation.ReconciliationIssueService;
import com.axon.messaging.topic.KafkaTopics;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class UserSummaryRecoveryConsumer {
    private final ObjectMapper objectMapper;
    private final UserSummaryService summaries;
    private final ReconciliationIssueService issues;

    @KafkaListener(topics = KafkaTopics.USER_SUMMARY_PROJECTION_FAILED,
            groupId = "axon-user-summary-recovery-v1",
            containerFactory = "userSummaryRecoveryKafkaListenerContainerFactory",
            properties = {"auto.offset.reset=earliest",
                    "value.deserializer=org.apache.kafka.common.serialization.StringDeserializer"})
    public void consume(String payload) {
        for (Long userId : userIds(objectMapper, payload)) {
            summaries.rebuildPurchaseSummary(userId);
            issues.resolveUserSummaryMismatch(userId);
            log.info("UserSummary rebuilt from purchase ledger: userId={}", userId);
        }
    }

    public static Set<Long> userIds(ObjectMapper objectMapper, String payload) {
        try {
            JsonNode event = objectMapper.readTree(payload);
            if (event == null || event.path("schemaVersion").asInt() != 1
                    || !event.path("targets").isArray() || event.path("targets").isEmpty()) {
                throw new IllegalArgumentException("Invalid projection failure event");
            }
            Set<Long> ids = new LinkedHashSet<>();
            for (JsonNode target : event.path("targets")) {
                JsonNode id = target.path("userId");
                if (!id.isIntegralNumber() || !id.canConvertToLong() || id.longValue() <= 0) {
                    throw new IllegalArgumentException("Invalid projection failure userId");
                }
                ids.add(id.longValue());
            }
            return ids;
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("Invalid projection failure JSON", e);
        }
    }
}
