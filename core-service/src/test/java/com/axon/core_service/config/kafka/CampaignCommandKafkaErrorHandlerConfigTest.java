package com.axon.core_service.config.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.axon.core_service.commandprocessing.OffsetCommitBlockedException;
import com.axon.core_service.observability.CorePipelineMetrics;
import com.axon.messaging.MarketingActionFailureCategory;
import com.axon.messaging.dto.CampaignActivityKafkaProducerDto;
import com.axon.messaging.topic.KafkaTopics;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;

class CampaignCommandKafkaErrorHandlerConfigTest {

    private final CampaignCommandKafkaErrorHandlerConfig config = new CampaignCommandKafkaErrorHandlerConfig();

    @Test
    void publishesOnlyFailedCommandToDltAndRecordsFailureDetails() {
        KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
        CorePipelineMetrics metrics = mock(CorePipelineMetrics.class);
        CampaignActivityKafkaProducerDto command = CampaignActivityKafkaProducerDto.builder()
                .dispatchId(45L)
                .build();
        ConsumerRecord<String, Object> record = new ConsumerRecord<>(
                KafkaTopics.CAMPAIGN_ACTIVITY_COMMAND, 0, 17L, "key", command);
        when(kafkaTemplate.send(KafkaTopics.CAMPAIGN_ACTIVITY_COMMAND_DLT, command))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        ConsumerRecordRecoverer recoverer = config.campaignCommandDltRecoverer(kafkaTemplate, metrics);

        recoverer.accept(record, new IllegalArgumentException("invalid campaign"));

        assertThat(command.getFailureReason()).isEqualTo("invalid campaign");
        assertThat(command.getFailureCategory()).isEqualTo(MarketingActionFailureCategory.UNKNOWN);
        verify(kafkaTemplate).send(KafkaTopics.CAMPAIGN_ACTIVITY_COMMAND_DLT, command);
        verify(metrics).recordDltRouted("campaign-command", 1);
    }

    @Test
    void dltPublishFailurePropagatesForOffsetRedelivery() {
        KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
        CorePipelineMetrics metrics = mock(CorePipelineMetrics.class);
        CampaignActivityKafkaProducerDto command = CampaignActivityKafkaProducerDto.builder().build();
        ConsumerRecord<String, Object> record = new ConsumerRecord<>(
                KafkaTopics.CAMPAIGN_ACTIVITY_COMMAND, 0, 17L, "key", command);
        when(kafkaTemplate.send(KafkaTopics.CAMPAIGN_ACTIVITY_COMMAND_DLT, command))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("DLT unavailable")));
        ConsumerRecordRecoverer recoverer = config.campaignCommandDltRecoverer(kafkaTemplate, metrics);

        assertThatThrownBy(() -> recoverer.accept(record, new IllegalArgumentException("invalid campaign")))
                .hasRootCauseMessage("DLT unavailable");
    }

    @Test
    void offsetBlockingFailuresAreNotConvertedIntoCommandDltRecords() {
        KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
        CorePipelineMetrics metrics = mock(CorePipelineMetrics.class);
        CampaignActivityKafkaProducerDto command = CampaignActivityKafkaProducerDto.builder().build();
        ConsumerRecord<String, Object> record = new ConsumerRecord<>(
                KafkaTopics.CAMPAIGN_ACTIVITY_COMMAND, 0, 17L, "key", command);
        ConsumerRecordRecoverer recoverer = config.campaignCommandDltRecoverer(kafkaTemplate, metrics);

        assertThatThrownBy(() -> recoverer.accept(record,
                new OffsetCommitBlockedException("projection failure record unavailable", new RuntimeException())))
                .isInstanceOf(OffsetCommitBlockedException.class);
        org.mockito.Mockito.verifyNoInteractions(kafkaTemplate);
    }
}
