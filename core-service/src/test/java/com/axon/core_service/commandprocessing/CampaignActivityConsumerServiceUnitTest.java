package com.axon.core_service.commandprocessing;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

import com.axon.core_service.observability.CorePipelineMetrics;
import com.axon.messaging.CampaignActivityType;
import com.axon.messaging.dto.CampaignActivityKafkaProducerDto;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class CampaignActivityConsumerServiceUnitTest {

    @Test
    void commandDltPublishFailureEscapesKafkaListenerBoundary() {
        CampaignActivityCommandDispatcher dispatcher = Mockito.mock(CampaignActivityCommandDispatcher.class);
        CorePipelineMetrics metrics = Mockito.mock(CorePipelineMetrics.class);
        CampaignActivityConsumerService consumer = new CampaignActivityConsumerService(dispatcher, metrics);
        List<CampaignActivityKafkaProducerDto> messages = List.of(CampaignActivityKafkaProducerDto.builder()
                .campaignActivityType(CampaignActivityType.FIRST_COME_FIRST_SERVE)
                .campaignActivityId(1L).userId(1L).build());
        doAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run();
            return null;
        }).when(metrics).recordCommandFlush(anyInt(), any(Runnable.class));
        doThrow(new OffsetCommitBlockedException("command DLT unavailable", new IllegalStateException()))
                .when(dispatcher).dispatch(messages);

        assertThatThrownBy(() -> consumer.consume(messages))
                .isInstanceOf(OffsetCommitBlockedException.class);
    }
}
