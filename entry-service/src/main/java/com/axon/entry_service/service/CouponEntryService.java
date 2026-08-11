package com.axon.entry_service.service;

import com.axon.entry_service.dto.CouponRequestDto;
import com.axon.messaging.CampaignActivityType;
import com.axon.messaging.dto.CampaignActivityKafkaProducerDto;
import com.axon.messaging.topic.KafkaTopics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class CouponEntryService {
    private static final long BROKER_ACK_TIMEOUT_SECONDS = 5;

    private final CampaignActivityProducerService campaignActivityProducerService;

    public boolean publishCouponIssue(CouponRequestDto payload) {
        CampaignActivityKafkaProducerDto message = CampaignActivityKafkaProducerDto.builder()
                .userId(payload.userId())
                .campaignActivityId(payload.campaignActivityId())
                .campaignActivityType(payload.campaignActivityType())
                .productId(payload.productId())
                .timestamp(Instant.now().toEpochMilli())
                .build();
        try {
            campaignActivityProducerService.send(KafkaTopics.CAMPAIGN_ACTIVITY_COMMAND, message)
                    .get(BROKER_ACK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            log.info("Published coupon issue command for user {} activity {}",
                    payload.userId(), payload.campaignActivityId());
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.error("Coupon issue command interrupted for user {} activity {}",
                    payload.userId(), payload.campaignActivityId(), exception);
            return false;
        } catch (Exception exception) {
            log.error("Coupon issue command failed for user {} activity {}",
                    payload.userId(), payload.campaignActivityId(), exception);
            return false;
        }
    }
}
