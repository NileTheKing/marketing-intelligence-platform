package com.axon.core_service.observability;

import com.axon.core_service.commandprocessing.CampaignActivityCommandBuffer;
import com.axon.core_service.service.purchase.PurchaseHandler;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CorePipelineMetricsBinder {

    private final MeterRegistry meterRegistry;
    private final CampaignActivityCommandBuffer commandBuffer;
    private final PurchaseHandler purchaseHandler;

    @PostConstruct
    void bindQueueDepthGauges() {
        Gauge.builder("axon.pipeline.queue.depth", commandBuffer, CampaignActivityCommandBuffer::size)
                .tag("pipeline", "campaign-command")
                .register(meterRegistry);
        Gauge.builder("axon.pipeline.queue.depth", purchaseHandler, PurchaseHandler::bufferedPurchaseCount)
                .tag("pipeline", "purchase")
                .register(meterRegistry);
    }
}
