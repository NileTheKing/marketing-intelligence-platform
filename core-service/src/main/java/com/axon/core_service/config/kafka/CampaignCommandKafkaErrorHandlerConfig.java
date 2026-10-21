package com.axon.core_service.config.kafka;

import com.axon.core_service.commandprocessing.OffsetCommitBlockedException;
import com.axon.core_service.observability.CorePipelineMetrics;
import com.axon.messaging.MarketingActionFailureCategory;
import com.axon.messaging.dto.CampaignActivityKafkaProducerDto;
import com.axon.messaging.topic.KafkaTopics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Slf4j
@Configuration
public class CampaignCommandKafkaErrorHandlerConfig {

    private static final long RETRY_INTERVAL_MILLIS = 1_000L;
    private static final long RETRIES_AFTER_FIRST_ATTEMPT = 2L;

    @Bean
    ConsumerRecordRecoverer campaignCommandDltRecoverer(
            KafkaTemplate<String, Object> kafkaTemplate,
            CorePipelineMetrics pipelineMetrics) {
        return (record, failure) -> {
            if (hasCause(failure, OffsetCommitBlockedException.class)) {
                throw new OffsetCommitBlockedException("Command offset commit remains blocked", failure);
            }

            if (!(record.value() instanceof CampaignActivityKafkaProducerDto message)) {
                throw new IllegalStateException("Unexpected campaign command record in DLT recovery", failure);
            }

            Throwable rootCause = rootCause(failure);
            if (message.getFailureReason() == null) {
                message.setFailureReason(rootCause.getMessage() == null
                        ? rootCause.getClass().getSimpleName()
                        : rootCause.getMessage());
            }
            if (message.getFailureCategory() == null) {
                message.setFailureCategory(MarketingActionFailureCategory.UNKNOWN);
            }

            kafkaTemplate.send(KafkaTopics.CAMPAIGN_ACTIVITY_COMMAND_DLT, message).join();
            pipelineMetrics.recordDltRouted("campaign-command", 1);
            log.warn("Campaign command recovered to DLT: topic={}, partition={}, offset={}",
                    record.topic(), record.partition(), record.offset());
        };
    }

    private DefaultErrorHandler campaignCommandErrorHandler(ConsumerRecordRecoverer campaignCommandDltRecoverer) {
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(campaignCommandDltRecoverer,
                new FixedBackOff(RETRY_INTERVAL_MILLIS, RETRIES_AFTER_FIRST_ATTEMPT));
        errorHandler.addNotRetryableExceptions(
                IllegalArgumentException.class,
                DataIntegrityViolationException.class,
                InvalidDataAccessApiUsageException.class,
                BadSqlGrammarException.class);
        return errorHandler;
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<Object, Object> campaignCommandKafkaListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory,
            ConsumerRecordRecoverer campaignCommandDltRecoverer) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, consumerFactory);
        factory.setCommonErrorHandler(campaignCommandErrorHandler(campaignCommandDltRecoverer));
        return factory;
    }

    private static boolean hasCause(Throwable failure, Class<? extends Throwable> expectedType) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (expectedType.isInstance(cause)) {
                return true;
            }
        }
        return false;
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }
}
