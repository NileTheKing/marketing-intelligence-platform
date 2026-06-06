package com.axon.core_service.service;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.axon.core_service.service.strategy.CampaignStrategy;
import com.axon.messaging.CampaignActivityType;
import com.axon.messaging.dto.CampaignActivityKafkaProducerDto;
import com.axon.messaging.topic.KafkaTopics;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

class CampaignActivityCommandDispatcherTest {

    @Test
    void dispatchSendsFailedBatchToDlt() {
        CampaignStrategy strategy = new FailingCampaignStrategy();
        KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
        CampaignActivityCommandDispatcher dispatcher =
                new CampaignActivityCommandDispatcher(List.of(strategy), kafkaTemplate);
        CampaignActivityKafkaProducerDto message = CampaignActivityKafkaProducerDto.builder()
                .campaignActivityType(CampaignActivityType.FIRST_COME_FIRST_SERVE)
                .campaignActivityId(1L)
                .userId(10L)
                .build();

        dispatcher.dispatch(List.of(message));

        verify(kafkaTemplate).send(eq(KafkaTopics.CAMPAIGN_ACTIVITY_COMMAND_DLT), eq(message));
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
}
