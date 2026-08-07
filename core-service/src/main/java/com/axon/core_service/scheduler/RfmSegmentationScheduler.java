package com.axon.core_service.scheduler;

import com.axon.core_service.domain.user.RfmSegment;
import com.axon.core_service.domain.user.UserSummary;
import com.axon.core_service.domain.dto.user.UserRfmMetricsDto;
import com.axon.core_service.repository.PurchaseRepository;
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
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class RfmSegmentationScheduler {

    private final UserSummaryRepository userSummaryRepository;
    private final PurchaseRepository purchaseRepository;
    private final RfmSegmentationService rfmSegmentationService;
    private final SchedulerExecutionLock schedulerExecutionLock;

    // 매일 새벽 4시 실행
    @Scheduled(cron = "0 0 4 * * *")
    @Transactional
    public void runRfmSegmentationBatch() {
        schedulerExecutionLock.runIfAcquired("rfm-segmentation", this::runRfmSegmentationBatchLocked);
    }

    private void runRfmSegmentationBatchLocked() {
        log.info("========== RFM Segmentation Batch Started ==========");

        int pageNum = 0;
        int pageSize = 100;
        LocalDateTime now = LocalDateTime.now();

        while (true) {
            Page<UserSummary> page = userSummaryRepository.findAll(PageRequest.of(pageNum, pageSize));
            if (page.isEmpty()) {
                break;
            }

            Map<Long, UserRfmMetricsDto> metricsByUserId = purchaseRepository
                    .findRfmMetricsByUserIdIn(page.getContent().stream().map(UserSummary::getUserId).toList())
                    .stream()
                    .collect(Collectors.toMap(UserRfmMetricsDto::userId, Function.identity()));

            for (UserSummary summary : page.getContent()) {
                Long userId = summary.getUserId();

                UserRfmMetricsDto metrics = metricsByUserId.get(userId);
                long frequency = metrics != null ? metrics.purchaseCount() : 0;

                RfmSegment segment = rfmSegmentationService.calculateSegment(
                        summary, frequency, metrics != null ? metrics.totalRevenue() : null, now);

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
}
