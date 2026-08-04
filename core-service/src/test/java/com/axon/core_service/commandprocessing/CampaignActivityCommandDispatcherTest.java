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
