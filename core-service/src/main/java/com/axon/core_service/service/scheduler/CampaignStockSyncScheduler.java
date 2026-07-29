package com.axon.core_service.service.scheduler;

import com.axon.core_service.scheduler.SchedulerExecutionLock;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "axon.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class CampaignStockSyncScheduler {

    private final CampaignStockSyncService campaignStockSyncService;
    private final SchedulerExecutionLock schedulerExecutionLock;

    @Scheduled(cron = "0 */5 * * * *")
    public void syncOngoingCampaignStocks() {
        schedulerExecutionLock.runIfAcquired("campaign-stock-sync",
                campaignStockSyncService::syncOngoingCampaignStocks);
    }
}
