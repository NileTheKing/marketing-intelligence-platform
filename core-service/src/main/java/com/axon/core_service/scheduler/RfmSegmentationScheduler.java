package com.axon.core_service.scheduler;

import com.axon.core_service.domain.user.RfmSegment;
import com.axon.core_service.domain.user.UserSummary;
import com.axon.core_service.domain.user.metric.UserMetric;
import com.axon.core_service.repository.UserMetricRepository;
import com.axon.core_service.repository.UserSummaryRepository;
import com.axon.core_service.service.RfmSegmentationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class RfmSegmentationScheduler {

    private final UserSummaryRepository userSummaryRepository;
    private final UserMetricRepository userMetricRepository;
    private final RfmSegmentationService rfmSegmentationService;

    // 매일 새벽 4시 실행
    @Scheduled(cron = "0 0 4 * * *")
    @Transactional
    public void runRfmSegmentationBatch() {
        log.info("========== RFM Segmentation Batch Started ==========");

        int pageNum = 0;
        int pageSize = 100;
        LocalDateTime now = LocalDateTime.now();

        while (true) {
            Page<UserSummary> page = userSummaryRepository.findAll(PageRequest.of(pageNum, pageSize));
            if (page.isEmpty()) {
                break;
            }

            for (UserSummary summary : page.getContent()) {
                Long userId = summary.getUserId();

                // 1. UserMetric (Analytics Store)에서 사전 집계된 파생 지표 조회 (O(1))
                int frequency = getMetricValue(userId, "PURCHASE_COUNT");
                long monetary = getMetricValue(userId, "TOTAL_REVENUE");

                // 2. 비즈니스 로직에 기반한 등급 판정
                RfmSegment segment = rfmSegmentationService.calculateSegment(summary, frequency, monetary, now);

                // 3. Update Snapshot
                if (summary.getRfmSegment() != segment) {
                    summary.updateRfmSegment(segment);
                    log.debug("User {} segment updated to {}", userId, segment);
                }
            }

            // JPA 더티 체킹/saveAll을 통한 벌크 업데이트 진행
            userSummaryRepository.saveAll(page.getContent());

            if (page.isLast()) {
                break;
            }
            pageNum++;
        }

        log.info("========== RFM Segmentation Batch Completed ==========");
    }

    private int getMetricValue(Long userId, String metricName) {
        List<UserMetric> metrics = userMetricRepository.findByUserIdAndMetricName(userId, metricName);
        if (metrics.isEmpty()) return 0;

        // 기간(Window) 상관없이 누적된 지표 합산을 위해 sum 처리 (도메인 특성에 맞게 조정 가능)
        return metrics.stream()
                .mapToLong(UserMetric::getMetricValue)
                .sum() > 0 ? (int) metrics.stream().mapToLong(UserMetric::getMetricValue).sum() : 0;
    }
}
