package com.axon.core_service.scheduler;

import com.axon.core_service.domain.purchase.Purchase;
import com.axon.core_service.repository.PurchaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReconciliationScheduler {

    private final PurchaseRepository purchaseRepository;

    /**
     * [Ghost Data 탐지 대사 배치]
     * 매일 새벽 3시에 전일자 데이터를 대상으로 대사(Reconciliation)를 수행합니다.
     * 참여 기록(CampaignActivityEntry)은 없는데 결제 기록(Purchase)만 존재하는
     * 데이터 불일치 건(Ghost)을 찾아 Slack 또는 모니터링 툴(Sentry/Datadog)로 알림을 보냅니다.
     */
    @Scheduled(cron = "0 0 3 * * ?")
    @Transactional(readOnly = true)
    public void detectGhostPurchases() {
        // 대사 타겟 기간: 어제 자정(00:00:00) ~ 오늘 자정(00:00:00)
        LocalDate yesterday = LocalDate.now().minusDays(1);
        LocalDateTime startDate = yesterday.atStartOfDay();
        LocalDateTime endDate = LocalDate.now().atStartOfDay();

        log.info("[Reconciliation] 시작: Ghost Purchase 스캔 기간 {} ~ {}", startDate, endDate);

        List<Purchase> ghostPurchases = purchaseRepository.findGhostPurchases(startDate, endDate);

        if (ghostPurchases.isEmpty()) {
            log.info("[Reconciliation] 정상: 발견된 Ghost 데이터가 없습니다.");
            return;
        }

        // 고아 데이터 발견 시 강력한 에러 로깅 (Sentry, ELK, Datadog 알람 연동 목적)
        log.error("🚨 [GHOST DETECTED] 데이터 정합성 오류 발생! 참여 기록 없이 결제된 고아 데이터 {}건 발견!", ghostPurchases.size());
        
        for (Purchase ghost : ghostPurchases) {
            log.error("  👉 [Ghost Detail] Purchase ID: {}, User ID: {}, Activity ID: {}, PurchasedAt: {}",
                    ghost.getId(), ghost.getUserId(), ghost.getCampaignActivityId(), ghost.getPurchaseAt());
            
            // TODO: 서비스 규모 확장에 따라 아래와 같은 추가 파이프라인 연동 가능
            // 1. Slack Webhook을 통한 실시간 긴급 알림 (Customer Service 팀 전달용)
            // 2. 강제 결제 취소(환불) 및 UserSummary 롤백 로직 호출
            // slackNotificationService.sendEmergencyAlert("Ghost Data", ghost);
        }

        log.warn("[Reconciliation] 종료: 총 {}건의 수동 처리 필요.", ghostPurchases.size());
    }
}
