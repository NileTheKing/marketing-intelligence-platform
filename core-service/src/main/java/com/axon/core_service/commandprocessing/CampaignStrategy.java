package com.axon.core_service.commandprocessing;

import com.axon.messaging.CampaignActivityType;
import com.axon.messaging.dto.CampaignActivityKafkaProducerDto;

public interface CampaignStrategy {
    /**
 * Processes the campaign activity event described by the provided DTO.
 *
 * @param event the campaign activity event to handle
 */
void process(CampaignActivityKafkaProducerDto event);
    /**
 * Identifies the campaign activity handled by this strategy.
 *
 * @return the CampaignActivityType representing the activity this strategy handles
 */
CampaignActivityType getType();
}
