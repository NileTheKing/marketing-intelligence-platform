package com.axon.core_service.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.axon.messaging.dto.CampaignActivityKafkaProducerDto;
import org.junit.jupiter.api.Test;

class CampaignActivityCommandBufferTest {

    @Test
    void drainReturnsUpToMaxSizeAndKeepsRemainingMessages() {
        CampaignActivityCommandBuffer buffer = new CampaignActivityCommandBuffer();
        buffer.offer(message(1L));
        buffer.offer(message(2L));
        buffer.offer(message(3L));

        assertThat(buffer.drain(2))
                .extracting(CampaignActivityKafkaProducerDto::getUserId)
                .containsExactly(1L, 2L);
        assertThat(buffer.size()).isEqualTo(1);
        assertThat(buffer.drain(2))
                .extracting(CampaignActivityKafkaProducerDto::getUserId)
                .containsExactly(3L);
    }

    private CampaignActivityKafkaProducerDto message(Long userId) {
        return CampaignActivityKafkaProducerDto.builder()
                .userId(userId)
                .campaignActivityId(1L)
                .build();
    }
}
