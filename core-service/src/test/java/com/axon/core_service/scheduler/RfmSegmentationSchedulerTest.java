package com.axon.core_service.scheduler;

import com.axon.core_service.domain.dto.user.UserRfmMetricsDto;
import com.axon.core_service.domain.user.RfmSegment;
import com.axon.core_service.domain.user.UserSummary;
import com.axon.core_service.repository.PurchaseRepository;
import com.axon.core_service.repository.UserSummaryRepository;
import com.axon.core_service.service.RfmSegmentationService;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.*;

class RfmSegmentationSchedulerTest {

    @Test
    void runRfmSegmentationBatch_usesPurchaseAggregateAndUpdatesSegment() {
        UserSummaryRepository userSummaryRepository = mock(UserSummaryRepository.class);
        PurchaseRepository purchaseRepository = mock(PurchaseRepository.class);
        RfmSegmentationScheduler scheduler = new RfmSegmentationScheduler(
                userSummaryRepository, purchaseRepository, new RfmSegmentationService());
        UserSummary summary = mock(UserSummary.class);
        when(summary.getUserId()).thenReturn(10L);
        when(summary.getLastPurchaseAt()).thenReturn(LocalDateTime.now().minusDays(10));
        when(userSummaryRepository.findAll(PageRequest.of(0, 100)))
                .thenReturn(new PageImpl<>(List.of(summary), PageRequest.of(0, 100), 1));
        when(purchaseRepository.findRfmMetricsByUserIdIn(List.of(10L)))
                .thenReturn(List.of(new UserRfmMetricsDto(10L, 3, BigDecimal.valueOf(100_000))));

        scheduler.runRfmSegmentationBatch();

        verify(purchaseRepository).findRfmMetricsByUserIdIn(List.of(10L));
        verify(summary).updateRfmSegment(RfmSegment.VIP);
        verify(userSummaryRepository).saveAll(List.of(summary));
    }
}
