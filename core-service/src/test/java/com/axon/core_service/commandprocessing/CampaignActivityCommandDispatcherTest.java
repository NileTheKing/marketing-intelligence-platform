package com.axon.core_service.commandprocessing;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.axon.core_service.observability.CorePipelineMetrics;
import com.axon.messaging.CampaignActivityType;
import com.axon.messaging.dto.CampaignActivityKafkaProducerDto;
import com.axon.messaging.topic.KafkaTopics;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

class CampaignActivityCommandDispatcherTest {

    @Test
    void dispatchSendsFailedBatchToDlt() {
        CampaignStrategy strategy = new FailingCampaignStrategy();
        KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
        CorePipelineMetrics pipelineMetrics = mock(CorePipelineMetrics.class);
        CampaignActivityKafkaProducerDto message = message();
        when(kafkaTemplate.send(KafkaTopics.CAMPAIGN_ACTIVITY_COMMAND_DLT, message))
                .thenReturn(CompletableFuture.completedFuture(null));
        CampaignActivityCommandDispatcher dispatcher =
                new CampaignActivityCommandDispatcher(List.of(strategy), kafkaTemplate, pipelineMetrics);

        dispatcher.dispatch(List.of(message));

        verify(kafkaTemplate).send(eq(KafkaTopics.CAMPAIGN_ACTIVITY_COMMAND_DLT), eq(message));
        verify(pipelineMetrics).recordDltRouted("campaign-command", 1);
    }

    @Test
    void dispatchPropagatesDltFailureSoListenerCannotCommitOffset() {
        CampaignStrategy strategy = new FailingCampaignStrategy();
        KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
        CorePipelineMetrics pipelineMetrics = mock(CorePipelineMetrics.class);
        CampaignActivityKafkaProducerDto message = message();
        when(kafkaTemplate.send(KafkaTopics.CAMPAIGN_ACTIVITY_COMMAND_DLT, message))
                .thenReturn(CompletableFuture.failedFuture(
                        new IllegalStateException("dlt unavailable")));
        CampaignActivityCommandDispatcher dispatcher =
                new CampaignActivityCommandDispatcher(List.of(strategy), kafkaTemplate, pipelineMetrics);

        assertThatThrownBy(() -> dispatcher.dispatch(List.of(message)))
                .hasRootCauseMessage("dlt unavailable");
    }

    @Test
    void dispatchDoesNotRouteWholeBatchWhenStrategyAlreadyBlockedOffsetCommit() {
        CampaignStrategy strategy = new FailingOffsetCommitStrategy();
        KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
        CorePipelineMetrics pipelineMetrics = mock(CorePipelineMetrics.class);
        CampaignActivityCommandDispatcher dispatcher =
                new CampaignActivityCommandDispatcher(List.of(strategy), kafkaTemplate, pipelineMetrics);

        assertThatThrownBy(() -> dispatcher.dispatch(List.of(message())))
                .isInstanceOf(OffsetCommitBlockedException.class);
        org.mockito.Mockito.verifyNoInteractions(kafkaTemplate);
    }

    @Test
    void dispatchForwardsLegacyWebhookWithoutCallingExternalStrategy() {
        CampaignStrategy webhookStrategy = mock(CampaignStrategy.class);
        when(webhookStrategy.getType()).thenReturn(CampaignActivityType.WEBHOOK);
        KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
        CorePipelineMetrics pipelineMetrics = mock(CorePipelineMetrics.class);
        CampaignActivityKafkaProducerDto webhook = CampaignActivityKafkaProducerDto.builder()
                .campaignActivityType(CampaignActivityType.WEBHOOK)
                .userId(10L)
                .build();
        when(kafkaTemplate.send(KafkaTopics.WEBHOOK_COMMAND, webhook))
                .thenReturn(CompletableFuture.completedFuture(null));
        CampaignActivityCommandDispatcher dispatcher =
                new CampaignActivityCommandDispatcher(List.of(webhookStrategy), kafkaTemplate, pipelineMetrics);

        dispatcher.dispatch(List.of(webhook));

        verify(kafkaTemplate).send(KafkaTopics.WEBHOOK_COMMAND, webhook);
        org.mockito.Mockito.verify(webhookStrategy, org.mockito.Mockito.never()).process(webhook);
    }

    @Test
    void dispatchRoutesUnsupportedTypeToDltInsteadOfCommittingSilently() {
        KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
        CorePipelineMetrics pipelineMetrics = mock(CorePipelineMetrics.class);
        CampaignActivityKafkaProducerDto unsupported = CampaignActivityKafkaProducerDto.builder()
                .campaignActivityType(CampaignActivityType.GIVEAWAY)
                .userId(10L)
                .build();
        when(kafkaTemplate.send(KafkaTopics.CAMPAIGN_ACTIVITY_COMMAND_DLT, unsupported))
                .thenReturn(CompletableFuture.completedFuture(null));
        CampaignActivityCommandDispatcher dispatcher =
                new CampaignActivityCommandDispatcher(List.of(), kafkaTemplate, pipelineMetrics);

        dispatcher.dispatch(List.of(unsupported));

        verify(kafkaTemplate).send(KafkaTopics.CAMPAIGN_ACTIVITY_COMMAND_DLT, unsupported);
        verify(pipelineMetrics).recordDltRouted("campaign-command", 1);
    }

    @Test
    void dispatchRoutesMissingTypeToDltInsteadOfFailingDuringGrouping() {
        KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
        CorePipelineMetrics pipelineMetrics = mock(CorePipelineMetrics.class);
        CampaignActivityKafkaProducerDto missingType = CampaignActivityKafkaProducerDto.builder()
                .userId(10L)
                .build();
        when(kafkaTemplate.send(KafkaTopics.CAMPAIGN_ACTIVITY_COMMAND_DLT, missingType))
                .thenReturn(CompletableFuture.completedFuture(null));
        CampaignActivityCommandDispatcher dispatcher =
                new CampaignActivityCommandDispatcher(List.of(), kafkaTemplate, pipelineMetrics);

        dispatcher.dispatch(List.of(missingType));

        verify(kafkaTemplate).send(KafkaTopics.CAMPAIGN_ACTIVITY_COMMAND_DLT, missingType);
        verify(pipelineMetrics).recordDltRouted("campaign-command", 1);
    }

    private static CampaignActivityKafkaProducerDto message() {
        return CampaignActivityKafkaProducerDto.builder()
                .campaignActivityType(CampaignActivityType.FIRST_COME_FIRST_SERVE)
                .campaignActivityId(1L)
                .userId(10L)
                .build();
    }

    private static class FailingCampaignStrategy implements CampaignStrategy {
        @Override
        public void process(CampaignActivityKafkaProducerDto event) {
            throw new IllegalStateException("failed");
        }

        @Override
        public CampaignActivityType getType() {
            return CampaignActivityType.FIRST_COME_FIRST_SERVE;
        }
    }

    private static class FailingOffsetCommitStrategy implements CampaignStrategy {
        @Override
        public void process(CampaignActivityKafkaProducerDto event) {
            throw new OffsetCommitBlockedException("failure record unavailable", new IllegalStateException());
        }

        @Override
        public CampaignActivityType getType() {
            return CampaignActivityType.FIRST_COME_FIRST_SERVE;
        }
    }
}
