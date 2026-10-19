package com.axon.core_service.commandprocessing;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import org.springframework.kafka.listener.BatchListenerFailedException;

class CampaignActivityCommandDispatcherTest {

    @Test
    void reportsFailedMessageIndexInsteadOfRoutingWholeBatchToDlt() {
        CampaignStrategy strategy = new FailingCampaignStrategy();
        KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
        CorePipelineMetrics pipelineMetrics = mock(CorePipelineMetrics.class);
        CampaignActivityKafkaProducerDto message = message();
        CampaignActivityCommandDispatcher dispatcher =
                new CampaignActivityCommandDispatcher(List.of(strategy), kafkaTemplate, pipelineMetrics);

        assertThatThrownBy(() -> dispatcher.dispatch(List.of(message)))
                .isInstanceOf(BatchListenerFailedException.class)
                .satisfies(failure -> org.assertj.core.api.Assertions.assertThat(
                        ((BatchListenerFailedException) failure).getIndex()).isZero());

        org.mockito.Mockito.verifyNoInteractions(kafkaTemplate);
    }

    @Test
    void dispatchPropagatesDltFailureWithFailingMessageIndex() {
        KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
        CorePipelineMetrics pipelineMetrics = mock(CorePipelineMetrics.class);
        CampaignActivityKafkaProducerDto message = CampaignActivityKafkaProducerDto.builder()
                .campaignActivityType(CampaignActivityType.GIVEAWAY)
                .userId(10L)
                .build();
        when(kafkaTemplate.send(KafkaTopics.CAMPAIGN_ACTIVITY_COMMAND_DLT, message))
                .thenReturn(CompletableFuture.failedFuture(
                        new IllegalStateException("dlt unavailable")));
        CampaignActivityCommandDispatcher dispatcher =
                new CampaignActivityCommandDispatcher(List.of(), kafkaTemplate, pipelineMetrics);

        assertThatThrownBy(() -> dispatcher.dispatch(List.of(message)))
                .isInstanceOf(BatchListenerFailedException.class)
                .hasRootCauseMessage("dlt unavailable")
                .satisfies(failure -> org.assertj.core.api.Assertions.assertThat(
                        ((BatchListenerFailedException) failure).getIndex()).isZero());
    }

    @Test
    void dispatchDoesNotRouteWholeBatchWhenStrategyAlreadyBlockedOffsetCommit() {
        CampaignStrategy strategy = new FailingOffsetCommitStrategy();
        KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
        CorePipelineMetrics pipelineMetrics = mock(CorePipelineMetrics.class);
        CampaignActivityCommandDispatcher dispatcher =
                new CampaignActivityCommandDispatcher(List.of(strategy), kafkaTemplate, pipelineMetrics);

        assertThatThrownBy(() -> dispatcher.dispatch(List.of(message())))
                .isInstanceOf(BatchListenerFailedException.class)
                .hasCauseInstanceOf(OffsetCommitBlockedException.class);
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

    @Test
    void mapsStrategyFailureIndexBackToOriginalListenerBatch() {
        CampaignStrategy couponStrategy = new CampaignStrategy() {
            @Override
            public void process(CampaignActivityKafkaProducerDto event) {
            }

            @Override
            public CampaignActivityType getType() {
                return CampaignActivityType.COUPON;
            }
        };
        BatchStrategy fcfsStrategy = new BatchStrategy() {
            @Override
            public void process(CampaignActivityKafkaProducerDto event) {
            }

            @Override
            public void processBatch(List<CampaignActivityKafkaProducerDto> messages) {
                throw new BatchListenerFailedException("record failed", new IllegalStateException("db"), 1);
            }

            @Override
            public CampaignActivityType getType() {
                return CampaignActivityType.FIRST_COME_FIRST_SERVE;
            }
        };
        CampaignActivityKafkaProducerDto coupon = message(CampaignActivityType.COUPON, 1L);
        CampaignActivityKafkaProducerDto firstFcfs = message(CampaignActivityType.FIRST_COME_FIRST_SERVE, 2L);
        CampaignActivityKafkaProducerDto secondFcfs = message(CampaignActivityType.FIRST_COME_FIRST_SERVE, 3L);
        CampaignActivityCommandDispatcher dispatcher = new CampaignActivityCommandDispatcher(
                List.of(couponStrategy, fcfsStrategy), mock(KafkaTemplate.class), mock(CorePipelineMetrics.class));

        assertThatThrownBy(() -> dispatcher.dispatch(List.of(coupon, firstFcfs, secondFcfs)))
                .isInstanceOf(BatchListenerFailedException.class)
                .satisfies(failure -> org.assertj.core.api.Assertions.assertThat(
                        ((BatchListenerFailedException) failure).getIndex()).isEqualTo(2));
    }

    @Test
    void propagatesUnindexedBatchFailureInsteadOfReprocessingMessagesIndividually() {
        BatchStrategy strategy = new BatchStrategy() {
            @Override
            public void process(CampaignActivityKafkaProducerDto event) {
                throw new AssertionError("batch failure must not trigger per-message processing");
            }

            @Override
            public void processBatch(List<CampaignActivityKafkaProducerDto> messages) {
                throw new IllegalStateException("batch database failure");
            }

            @Override
            public CampaignActivityType getType() {
                return CampaignActivityType.COUPON;
            }
        };
        CampaignActivityCommandDispatcher dispatcher = new CampaignActivityCommandDispatcher(
                List.of(strategy), mock(KafkaTemplate.class), mock(CorePipelineMetrics.class));

        assertThatThrownBy(() -> dispatcher.dispatch(List.of(
                message(CampaignActivityType.COUPON, 1L), message(CampaignActivityType.COUPON, 2L))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("batch database failure");
    }

    private static CampaignActivityKafkaProducerDto message() {
        return message(CampaignActivityType.FIRST_COME_FIRST_SERVE, 10L);
    }

    private static CampaignActivityKafkaProducerDto message(CampaignActivityType type, Long userId) {
        return CampaignActivityKafkaProducerDto.builder()
                .campaignActivityType(type)
                .campaignActivityId(1L)
                .userId(userId)
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
