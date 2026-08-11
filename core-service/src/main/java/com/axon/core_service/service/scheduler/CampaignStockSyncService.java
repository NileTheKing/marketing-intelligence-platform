package com.axon.core_service.service.scheduler;

import com.axon.core_service.domain.campaignactivity.CampaignActivity;
import com.axon.core_service.domain.dto.campaignactivity.CampaignActivityStatus;
import com.axon.core_service.domain.purchase.PurchaseStatus;
import com.axon.core_service.repository.CampaignActivityRepository;
import com.axon.core_service.repository.PurchaseRepository;
import com.axon.core_service.service.ProductService;
import com.axon.core_service.service.reconciliation.ReconciliationIssueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class CampaignStockSyncService {

    private final CampaignActivityRepository campaignActivityRepository;
    private final ProductService productService;
    private final RedisTemplate<String, String> redisTemplate;
    private final PurchaseRepository purchaseRepository;
    private final ReconciliationIssueService reconciliationIssueService;
    private final TransactionTemplate transactionTemplate;

    public void syncOngoingCampaignStocks() {
        List<Long> activeCampaignIds = campaignActivityRepository
                .findIdsByStatus(CampaignActivityStatus.ACTIVE);

        if (activeCampaignIds.isEmpty()) {
            return;
        }

        log.info("Found {} active campaigns to sync stock", activeCampaignIds.size());

        for (Long activityId : activeCampaignIds) {
            try {
                transactionTemplate.executeWithoutResult(status -> syncOneActivity(activityId, false));
            } catch (Exception e) {
                log.error("Failed to sync stock for activity {}: {}", activityId, e.getMessage());
            }
        }
    }

    private void syncOneActivity(Long activityId, boolean forceEnd) {
        CampaignActivity activity = campaignActivityRepository.findById(activityId)
                .orElseThrow(() -> new IllegalArgumentException("Activity not found: " + activityId));

        syncCampaignStock(activity);
        if (forceEnd || activity.getEndDate() != null && activity.getEndDate().isBefore(LocalDateTime.now())) {
            activity.updateStatus(CampaignActivityStatus.ENDED);
            log.info("Activity {} marked as ENDED after final sync", activity.getId());
        }
    }

    private void syncCampaignStock(CampaignActivity activity) {
        String counterKey = "campaign:" + activity.getId() + ":counter";

        String soldCountStr = redisTemplate.opsForValue().get(counterKey);
        long redisSoldCount = soldCountStr != null ? Long.parseLong(soldCountStr) : 0L;

        long persistedPurchaseCount = purchaseRepository.countByCampaignActivityId(activity.getId());
        long confirmedPurchaseCount = purchaseRepository.countByCampaignActivityIdAndStatus(
                activity.getId(), PurchaseStatus.CONFIRMED);

        if (redisSoldCount != persistedPurchaseCount) {
            log.warn("[RECONCILIATION] Discrepancy in activity {}: Redis={}, MySQL={}",
                    activity.getId(), redisSoldCount, persistedPurchaseCount);
            reconciliationIssueService.detectRedisPurchaseCountMismatch(
                    activity.getId(), redisSoldCount, persistedPurchaseCount);
        }

        if (activity.getProductId() == null) return;

        long alreadySynced = activity.getSyncedCount() != null ? activity.getSyncedCount() : 0L;
        long delta = confirmedPurchaseCount - alreadySynced;

        if (delta != 0) {
            productService.syncCampaignStock(activity.getProductId(), delta);
            activity.updateSyncedCount((int) confirmedPurchaseCount);
        }
    }

    public void syncCampaignStockManually(Long campaignActivityId) {
        log.info("Manual sync requested for activity {}", campaignActivityId);
        transactionTemplate.executeWithoutResult(status -> syncOneActivity(campaignActivityId, true));
    }
}
