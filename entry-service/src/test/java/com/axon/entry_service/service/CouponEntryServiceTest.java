package com.axon.entry_service.service;

import com.axon.entry_service.dto.CouponRequestDto;
import com.axon.messaging.CampaignActivityType;
import com.axon.messaging.dto.CampaignActivityKafkaProducerDto;
import com.axon.messaging.topic.KafkaTopics;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CouponEntryServiceTest {

    @Test
    void publishCouponIssueSucceedsOnlyAfterBrokerAck() {
        CampaignActivityProducerService producer = mock(CampaignActivityProducerService.class);
        when(producer.send(
                org.mockito.ArgumentMatchers.eq(KafkaTopics.CAMPAIGN_ACTIVITY_COMMAND),
                any(CampaignActivityKafkaProducerDto.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
        CouponEntryService service = new CouponEntryService(producer);

        boolean published = service.publishCouponIssue(payload());

        assertThat(published).isTrue();
    }

    @Test
    void publishCouponIssueReportsBrokerFailure() {
        CampaignActivityProducerService producer = mock(CampaignActivityProducerService.class);
        when(producer.send(
                org.mockito.ArgumentMatchers.eq(KafkaTopics.CAMPAIGN_ACTIVITY_COMMAND),
                any(CampaignActivityKafkaProducerDto.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")));
        CouponEntryService service = new CouponEntryService(producer);

        boolean published = service.publishCouponIssue(payload());

        assertThat(published).isFalse();
    }

    private CouponRequestDto payload() {
        return new CouponRequestDto(1L, 10L, 100L, CampaignActivityType.COUPON);
    }
}
