package com.axon.core_service.service;

import com.axon.messaging.dto.CampaignActivityKafkaProducerDto;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.springframework.stereotype.Component;

@Component
public class CampaignActivityCommandBuffer {

    private final ConcurrentLinkedQueue<CampaignActivityKafkaProducerDto> buffer = new ConcurrentLinkedQueue<>();

    public void offer(CampaignActivityKafkaProducerDto message) {
        buffer.offer(message);
    }

    public boolean isEmpty() {
        return buffer.isEmpty();
    }

    public int size() {
        return buffer.size();
    }

    public List<CampaignActivityKafkaProducerDto> drain(int maxSize) {
        List<CampaignActivityKafkaProducerDto> drained = new ArrayList<>(maxSize);

        for (int i = 0; i < maxSize; i++) {
            CampaignActivityKafkaProducerDto message = buffer.poll();
            if (message == null) {
                break;
            }
            drained.add(message);
        }

        return drained;
    }
}
