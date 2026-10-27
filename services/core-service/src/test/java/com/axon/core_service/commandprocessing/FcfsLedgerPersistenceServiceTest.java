package com.axon.core_service.commandprocessing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.*;

import com.axon.core_service.campaign.CampaignActivityRepository;
import com.axon.core_service.campaign.domain.CampaignActivity;
import com.axon.core_service.domain.purchase.Purchase;
import com.axon.core_service.domain.purchase.PurchaseType;
import com.axon.core_service.entry.CampaignActivityEntryRepository;
import com.axon.core_service.observability.CorePipelineMetrics;
import com.axon.core_service.repository.PurchaseRepository;
import com.axon.core_service.service.UserSummaryService;
import com.axon.core_service.event.CampaignActivityApprovedEvent;
import com.axon.messaging.dto.CampaignActivityKafkaProducerDto;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;

class FcfsLedgerPersistenceServiceTest {
    @Test
    void duplicateBatchAndRedeliveryOnlyInsertAndPublishOnce() {
        var activities = mock(CampaignActivityRepository.class);
        var entries = mock(CampaignActivityEntryRepository.class);
        var purchases = mock(PurchaseRepository.class);
        var jdbc = mock(JdbcTemplate.class);
        var activity = mock(CampaignActivity.class);
        when(activity.getId()).thenReturn(1L);
        when(activity.getCampaignId()).thenReturn(2L);
        when(activity.getPrice()).thenReturn(BigDecimal.TEN);
        when(activities.findAllById(any())).thenReturn(List.of(activity));
        when(entries.findByActivityIdsAndUserIds(any(), any())).thenReturn(List.of());
        Instant time = Instant.parse("2026-08-01T00:00:00Z");
        var stored = new Purchase(3L, 4L, 1L, PurchaseType.CAMPAIGNACTIVITY, BigDecimal.TEN, 1, time);
        when(purchases.findByCampaignActivityIdsAndUserIds(any(), any()))
                .thenReturn(List.of(), List.of(stored));
        var ledger = new FcfsLedgerPersistenceService(activities, entries, purchases, jdbc);
        var events = mock(ApplicationEventPublisher.class);
        var metrics = mock(CorePipelineMetrics.class);
        doAnswer(call -> { call.getArgument(1, Runnable.class).run(); return null; })
                .when(metrics).recordPurchaseFlush(anyInt(), any(Runnable.class));
        var orchestrator = new FcfsCommandOrchestrator(ledger, mock(UserSummaryService.class),
                mock(UserSummaryProjectionFailurePublisher.class), events, metrics);
        var first = CampaignActivityKafkaProducerDto.builder().campaignActivityId(1L).userId(3L)
                .productId(4L).timestamp(time.toEpochMilli()).build();
        var duplicate = CampaignActivityKafkaProducerDto.builder().campaignActivityId(1L).userId(3L)
                .productId(4L).timestamp(time.plusSeconds(3600).toEpochMilli()).build();

        orchestrator.process(List.of(first, duplicate));
        orchestrator.process(List.of(duplicate));

        var args = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(startsWith("INSERT INTO purchases "), args.capture());
        assertThat(args.getValue()).hasSize(10);
        verify(events, times(1)).publishEvent(any(CampaignActivityApprovedEvent.class));
    }
}
