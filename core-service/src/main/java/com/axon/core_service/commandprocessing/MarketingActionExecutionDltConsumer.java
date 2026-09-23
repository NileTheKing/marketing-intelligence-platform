package com.axon.core_service.commandprocessing;

import com.axon.core_service.service.MarketingActionExecutionService;
import com.axon.messaging.dto.CampaignActivityKafkaProducerDto;
import com.axon.messaging.topic.KafkaTopics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class MarketingActionExecutionDltConsumer {

    private final MarketingActionExecutionService executionService;

    @KafkaListener(topics = KafkaTopics.CAMPAIGN_ACTIVITY_COMMAND_DLT,
            groupId = "axon-marketing-action-campaign-dlt-group")
    public void consumeCampaignCommandDlt(List<CampaignActivityKafkaProducerDto> messages) {
        messages.forEach(message -> {
            if (message.getFailureCategory() == null) {
                executionService.markDltFinal(message.getDispatchId(), message.getFailureReason());
            } else {
                executionService.markDltFinal(message.getDispatchId(), message.getFailureCategory(),
                        message.getFailureReason());
            }
        });
    }

    @KafkaListener(topics = KafkaTopics.WEBHOOK_FAILED_DLT,
            groupId = "axon-marketing-action-webhook-dlt-group")
    public void consumeWebhookDlt(List<WebhookFailedDelivery> messages) {
        messages.forEach(message -> {
            if (message.getFailureCategory() == null) {
                executionService.markDltFinal(message.getDispatchId(), message.getFailureReason());
            } else {
                executionService.markDltFinal(message.getDispatchId(), message.getFailureCategory(),
                        message.getFailureReason());
            }
        });
    }
}
