package com.axon.core_service.service;

import com.axon.core_service.observability.CorePipelineMetrics;
import com.axon.messaging.dto.CampaignActivityKafkaProducerDto;
import com.axon.messaging.topic.KafkaTopics;

import java.util.List;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class CampaignActivityConsumerService {

    private final int kafkaBatchBuffer = 20;
    private final CampaignActivityCommandBuffer buffer;
    private final CampaignActivityCommandDispatcher dispatcher;
    private final CorePipelineMetrics pipelineMetrics;

    /**
     * Handles an incoming campaign activity message from the CAMPAIGN_ACTIVITY_COMMAND topic and delegates processing to the matching CampaignStrategy.
     *
     * If a strategy for the message's CampaignActivityType exists, the strategy's processing is invoked; otherwise a warning is logged. The consumed message is also logged.
     *
     * @param message the incoming campaign activity message to process
     */
    @KafkaListener(topics = KafkaTopics.CAMPAIGN_ACTIVITY_COMMAND, groupId = "axon-group")
    public void consume(CampaignActivityKafkaProducerDto message) {
        buffer.offer(message);
        log.info("📥 [Kafka] Consumed message: userId={}, activityId={}, bufferSize={}",
            message.getUserId(), message.getCampaignActivityId(), buffer.size());
    }
    /**
     * 100ms마다 자동으로 버퍼 플러시
     *
     * 역할: 메시지가 적게 들어와도 100ms 이내에 처리 보장
     */
    @Scheduled(fixedDelay = 100)
    public void scheduledFlush() {
        if (!buffer.isEmpty()) {
            flushBatch();
        }
    }

    /**
     * 버퍼의 메시지를 배치로 처리
     *
     * 역할:
     * 1. 버퍼에서 메시지 추출
     * 2. 타입별로 그룹핑
     * 3. 각 Strategy에 배치 처리 위임
     */
    private synchronized void flushBatch() {
        if (buffer.isEmpty()) {
            return;
        }

        List<CampaignActivityKafkaProducerDto> messages = drainBuffer();

        if (messages.isEmpty()) {
            return;
        }

        log.info("Processing Micro batch: {} messages", messages.size());
        pipelineMetrics.recordCommandFlush(messages.size(), () -> dispatcher.dispatch(messages));
    }
    /**
     * 버퍼에서 메시지 추출
     *
     * 역할: Thread-safe하게 버퍼 비우기 (최대 BATCH_SIZE개)
     */
    private List<CampaignActivityKafkaProducerDto> drainBuffer() {
        return buffer.drain(kafkaBatchBuffer);
    }

    /**
     * 서비스 종료 시 남은 메시지 처리
     */
    @PreDestroy
    public void onShutdown() {
        log.info("Shutting down consumer, flushing remaining messages...");
        flushBatch();
    }

}
