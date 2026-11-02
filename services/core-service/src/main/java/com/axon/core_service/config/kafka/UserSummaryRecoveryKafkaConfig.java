package com.axon.core_service.config.kafka;

import com.axon.core_service.commandprocessing.UserSummaryRecoveryConsumer;
import com.axon.core_service.service.UserSummaryService;
import com.axon.core_service.service.reconciliation.ReconciliationIssueService;
import com.axon.messaging.topic.KafkaTopics;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
@Slf4j
public class UserSummaryRecoveryKafkaConfig {
    @Bean
    ConcurrentKafkaListenerContainerFactory<Object, Object> userSummaryRecoveryKafkaListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory,
            KafkaTemplate<String, Object> kafka,
            ObjectMapper objectMapper,
            UserSummaryService summaries,
            ReconciliationIssueService issues) {
        var factory = new ConcurrentKafkaListenerContainerFactory<Object, Object>();
        configurer.configure(factory, consumerFactory);
        factory.setBatchListener(false);
        factory.setCommonErrorHandler(new DefaultErrorHandler((record, error) -> {
            String payload = (String) record.value();
            Set<Long> userIds;
            try {
                userIds = UserSummaryRecoveryConsumer.userIds(objectMapper, payload);
            } catch (IllegalArgumentException invalid) {
                kafka.send(KafkaTopics.USER_SUMMARY_PROJECTION_FAILED_DLT, Map.of(
                        "sourceTopic", record.topic(), "partition", record.partition(),
                        "offset", record.offset(), "payload", payload == null ? "" : payload,
                        "reason", invalid.toString())).join();
                log.warn("Invalid UserSummary failure message sent to DLT: partition={}, offset={}",
                        record.partition(), record.offset());
                return;
            }
            for (Long userId : userIds) {
                try {
                    summaries.rebuildPurchaseSummary(userId);
                } catch (RuntimeException failure) {
                    issues.detectUserSummaryRecoveryFailure(userId, failure.toString());
                    log.warn("UserSummary recovery failed and issue recorded: userId={}", userId, failure);
                    continue;
                }
                issues.resolveUserSummaryMismatch(userId);
            }
        }, new FixedBackOff(1_000L, 2L)));
        return factory;
    }
}
