package com.axon.core_service.commandprocessing;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.axon.messaging.CampaignActivityType;
import com.axon.messaging.dto.CampaignActivityKafkaProducerDto;
import java.util.List;
import org.junit.jupiter.api.Test;

class WebhookCommandConsumerServiceTest {

    @Test
    void delegatesIsolatedWebhookBatch() {
        WebhookStrategy strategy = mock(WebhookStrategy.class);
        WebhookCommandConsumerService consumer = new WebhookCommandConsumerService(strategy);
        List<CampaignActivityKafkaProducerDto> messages = List.of(
                CampaignActivityKafkaProducerDto.builder()
                        .campaignActivityType(CampaignActivityType.WEBHOOK)
                        .userId(1L)
                        .build());

        consumer.consume(messages);

        verify(strategy).processBatch(messages);
    }

    @Test
    void ignoresEmptyPoll() {
        WebhookStrategy strategy = mock(WebhookStrategy.class);

        new WebhookCommandConsumerService(strategy).consume(List.of());

        verifyNoInteractions(strategy);
    }
}
