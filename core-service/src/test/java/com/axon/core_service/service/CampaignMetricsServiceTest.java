package com.axon.core_service.service;

import com.axon.core_service.domain.campaignactivityentry.CampaignActivityEntryStatus;
import com.axon.core_service.repository.CampaignActivityEntryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CampaignMetricsService TDD 기반 단위 테스트")
public class CampaignMetricsServiceTest {

    @Mock
    private CampaignActivityEntryRepository entryRepository;

    @InjectMocks
    private CampaignMetricsService service;

    @Test
    @DisplayName("정상 케이스: 특정 기간 내 승인된 엔트리 수 조회")
    void getApprovedCount_Success() {
        // given (Arrange)
        Long activityId = 1L;
        LocalDateTime startTime = LocalDateTime.of(2025, 1, 1, 0, 0);
        LocalDateTime endTime = LocalDateTime.of(2025, 1, 31, 23, 59);
        when(entryRepository.countByCampaignActivity_IdAndStatusAndCreatedAtBetween(
                activityId,
                CampaignActivityEntryStatus.APPROVED,
                startTime,
                endTime
        )).thenReturn(100L);

        // when (Act)
        Long count = service.getApprovedCount(activityId, startTime, endTime);

        // then (Assert)
        assertThat(count).isEqualTo(100L);
        verify(entryRepository).countByCampaignActivity_IdAndStatusAndCreatedAtBetween(
                activityId,
                CampaignActivityEntryStatus.APPROVED,
                startTime,
                endTime
        );
    }

    @Test
    @DisplayName("엣지 케이스: 데이터가 없을 때 0을 반환해야 함")
    void getApprovedCount_ReturnsZeroWhenNoData() {
        // given
        Long activityId = 1L;
        LocalDateTime startTime = LocalDateTime.now().minusDays(1);
        LocalDateTime endTime = LocalDateTime.now();
        when(entryRepository.countByCampaignActivity_IdAndStatusAndCreatedAtBetween(
                activityId,
                CampaignActivityEntryStatus.APPROVED,
                startTime,
                endTime
        )).thenReturn(0L);

        // when
        Long count = service.getApprovedCount(activityId, startTime, endTime);

        // then
        assertThat(count).isZero();
    }
}
