package com.axon.core_service.service.scheduler;

import com.axon.core_service.domain.campaignactivity.CampaignActivity;
import com.axon.core_service.domain.dto.campaignactivity.CampaignActivityStatus;
import com.axon.core_service.repository.CampaignActivityRepository;
import com.axon.core_service.repository.PurchaseRepository;
import com.axon.core_service.service.ProductService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class CampaignStockSyncScheduler {

    private final CampaignActivityRepository campaignActivityRepository;
    private final ProductService productService;
    private final RedisTemplate<String, String> redisTemplate;
    private final PurchaseRepository purchaseRepository;

    @Scheduled(cron = "0 */5 * * * *")
    @Transactional
    public void syncOngoingCampaignStocks() {

        List<CampaignActivity> activeCampaigns = campaignActivityRepository
                .findAllByStatus(CampaignActivityStatus.ACTIVE);

        if (activeCampaigns.isEmpty()) {
            return;
        }

        log.info("Found {} active campaigns to sync stock", activeCampaigns.size());

        for (CampaignActivity activity : activeCampaigns) {
            try {
                syncCampaignStock(activity);
                if (activity.getEndDate() != null && activity.getEndDate().isBefore(LocalDateTime.now())) {
                    activity.updateStatus(CampaignActivityStatus.ENDED);
                    campaignActivityRepository.save(activity);
                    log.info("Activity {} marked as ENDED after final sync", activity.getId());
                }
            } catch (Exception e) {
                log.error("Failed to sync stock for activity {}: {}", activity.getId(), e.getMessage());
            }
        }
    }

    private void syncCampaignStock(CampaignActivity activity) {
        String counterKey = "campaign:" + activity.getId() + ":counter";

        String soldCountStr = redisTemplate.opsForValue().get(counterKey);
        long redisSoldCount = soldCountStr != null ? Long.parseLong(soldCountStr) : 0L;

        long mysqlSoldCount = purchaseRepository.countByCampaignActivityId(activity.getId());

        if (redisSoldCount != mysqlSoldCount) {
            log.warn("[RECONCILIATION] Discrepancy in activity {}: Redis={}, MySQL={}",
                    activity.getId(), redisSoldCount, mysqlSoldCount);
        }

        if (activity.getProductId() == null) return;

        long alreadySynced = activity.getSyncedCount() != null ? activity.getSyncedCount() : 0L;
        long delta = mysqlSoldCount - alreadySynced;

        if (delta > 0) {
            productService.syncCampaignStock(activity.getProductId(), delta);
            activity.updateSyncedCount((int) mysqlSoldCount);
        }
    }

    @Transactional
    public void syncCampaignStockManually(Long campaignActivityId) {
        CampaignActivity activity = campaignActivityRepository.findById(campaignActivityId)
                .orElseThrow(() -> new IllegalArgumentException("Activity not found: " + campaignActivityId));

        log.info("Manual sync requested for activity {}", campaignActivityId);
        syncCampaignStock(activity);
        activity.updateStatus(CampaignActivityStatus.ENDED);
        campaignActivityRepository.save(activity);
    }
}
