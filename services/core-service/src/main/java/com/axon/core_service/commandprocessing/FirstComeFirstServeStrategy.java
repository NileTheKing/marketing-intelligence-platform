package com.axon.core_service.commandprocessing;

import com.axon.messaging.CampaignActivityType;
import com.axon.messaging.dto.CampaignActivityKafkaProducerDto;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class FirstComeFirstServeStrategy implements BatchStrategy {

    private final FcfsCommandOrchestrator orchestrator;

    @Override
    public void process(CampaignActivityKafkaProducerDto eventDto) {
        orchestrator.process(List.of(eventDto));
    }

    @Override
    public void processBatch(List<CampaignActivityKafkaProducerDto> messages) {
        if (messages.isEmpty()) {
            return;
        }
        log.info("Processing FCFS batch: {} messages", messages.size());
        orchestrator.process(messages);
    }

    @Override
    public CampaignActivityType getType() {
        return CampaignActivityType.FIRST_COME_FIRST_SERVE;
    }
}
