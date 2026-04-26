package com.axon.core_service.service.scheduler;

import com.axon.core_service.domain.campaignactivity.CampaignActivity;
import com.axon.core_service.domain.dto.campaignactivity.CampaignActivityStatus;
import com.axon.core_service.repository.CampaignActivityRepository;
import com.axon.core_service.service.ProductService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Scheduler that syncs campaign product stock after campaigns end.
 *
 * For FCFS campaigns, stock is managed by Redis counter during the campaign.
 * This scheduler periodically checks for recently ended campaigns and syncs
 * their Product.stock from Redis counter to MySQL.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CampaignStockSyncScheduler {

    private final CampaignActivityRepository campaignActivityRepository;
    private final ProductService productService;
    private final RedisTemplate<String, String> redisTemplate;

    /**
     * Runs every 5 minutes to sync stock for ended campaigns.
     */
    @Scheduled(cron = "0 */5 * * * *") // Every 5 minutes
    @Transactional
    public void syncEndedCampaignStocks() {
        log.debug("Checking for ended campaigns to sync stock...");

        // Find campaigns that ended in the last 10 minutes and are still ACTIVE
        LocalDateTime tenMinutesAgo = LocalDateTime.now().minusMinutes(10);
        LocalDateTime now = LocalDateTime.now();

        List<CampaignActivity> endedCampaigns = campaignActivityRepository
                .findByEndDateBetweenAndStatus(tenMinutesAgo, now, CampaignActivityStatus.ACTIVE);

        if (endedCampaigns.isEmpty()) {
            log.debug("No ended campaigns found");
            return;
        }

        log.info("Found {} ended campaigns to sync", endedCampaigns.size());

        for (CampaignActivity campaign : endedCampaigns) {
            try {
                syncCampaignStock(campaign);
            } catch (Exception e) {
                log.error("Failed to sync stock for campaign {}: {}",
                    campaign.getId(), e.getMessage(), e);
            }
        }
    }

    private final com.axon.core_service.repository.PurchaseRepository purchaseRepository;

    /**
     * Syncs a single campaign's stock from Redis to MySQL with an Audit step.
     */
    private void syncCampaignStock(CampaignActivity campaign) {
        String counterKey = "campaign:" + campaign.getId() + ":counter";

        // 1. Redis에서 판매량(게이트 통과 수) 조회
        String soldCountStr = redisTemplate.opsForValue().get(counterKey);
        long redisSoldCount = soldCountStr != null ? Long.parseLong(soldCountStr) : 0L;

        // 2. MySQL에서 실제 결제 성공 건수(진실의 원천) 조회 (Index Range Scan 활용)
        long mysqlSoldCount = purchaseRepository.countByCampaignActivityId(campaign.getId());

        log.info("Auditing campaign {}: redisSoldCount={}, mysqlSoldCount={}, limit={}",
            campaign.getId(), redisSoldCount, mysqlSoldCount, campaign.getLimitCount());

        // 3. Audit (대사) 및 정합성 보정 로직
        long finalSoldCount = mysqlSoldCount; // 무조건 DB 장부를 최종 진실로 간주 (SSOT)

        if (redisSoldCount != mysqlSoldCount) {
            log.error("🚨 [RECONCILIATION DISCREPANCY] 캠페인 {} 정합성 오류 발견! Redis({}) vs MySQL({}). " +
                      "데이터 무결성을 위해 DB 장부 기준으로 재고를 정산합니다.", 
                      campaign.getId(), redisSoldCount, mysqlSoldCount);
            
            // Ghost Data가 있다면(Redis > MySQL), 차이만큼 로깅하여 추후 분석 가능하게 함
            if (redisSoldCount > mysqlSoldCount) {
                log.warn("  👉 Ghost Data 추정: {}건 (입구는 통과했으나 실제 결제 로그가 남지 않음)", 
                         redisSoldCount - mysqlSoldCount);
            }
        }

        // 4. 실질적인 DB 재고 차감 (Sync to MySQL Product.stock)
        if (campaign.getProductId() != null) {
            productService.syncCampaignStock(campaign.getProductId(), finalSoldCount);
        }

        // 5. 캠페인 상태를 ENDED로 변경하여 중복 정산 방지
        campaign.updateStatus(CampaignActivityStatus.ENDED);
        campaignActivityRepository.save(campaign);

        log.info("Campaign {} stock audit & sync completed successfully (Final count: {})", 
            campaign.getId(), finalSoldCount);
    }

    /**
     * Manual sync endpoint (for testing or admin use).
     * Can be called via REST API if needed.
     */
    @Transactional
    public void syncCampaignStockManually(Long campaignActivityId) {
        CampaignActivity campaign = campaignActivityRepository.findById(campaignActivityId)
                .orElseThrow(() -> new IllegalArgumentException("Campaign not found: " + campaignActivityId));

        log.info("Manual sync requested for campaign {}", campaignActivityId);
        syncCampaignStock(campaign);
    }
}
