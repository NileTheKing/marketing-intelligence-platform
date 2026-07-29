package com.axon.core_service.service.purchase;

import com.axon.core_service.domain.dto.purchase.PurchaseInfoDto;
import com.axon.core_service.domain.purchase.PurchaseType;
import com.axon.core_service.event.CampaignActivityApprovedEvent;
import com.axon.core_service.service.ProductService;
import com.axon.core_service.service.UserSummaryService;
import com.axon.core_service.observability.CorePipelineMetrics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PurchaseHandlerTest {

    @Mock
    private ProductService productService;

    @Mock
    private UserSummaryService userSummaryService;

    @Mock
    private PurchaseService purchaseService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private DeadLetterHandler<PurchaseInfoDto> deadLetterHandler;

    @Mock
    private CorePipelineMetrics pipelineMetrics;

    @InjectMocks
    private PurchaseHandler purchaseHandler;

    @BeforeEach
    void executeMeasuredPurchaseFlush() {
        lenient().doAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run();
            return null;
        }).when(pipelineMetrics).recordPurchaseFlush(anyInt(), any(Runnable.class));
    }

    @Test
    @DisplayName("일반 구매(SHOP) 이벤트는 즉시 처리되어야 한다")
    void handle_ShopPurchase_ProcessedImmediately() {
        // 1. Given: SHOP 타입의 PurchaseInfoDto 생성
        PurchaseInfoDto purchaseInfo = new PurchaseInfoDto(
                1L,
                1L,
                1L,
                1L,
                Instant.now(),
                PurchaseType.SHOP,
                BigDecimal.valueOf(10000),
                1,
                Instant.now()
        );
        // 2. When: purchaseHandler.handle() 호출
        purchaseHandler.handle(purchaseInfo);
        // 3. Then: Mock 객체들의 행위 검증 (verify)
        // - productService.decreaseStock() 호출 여부
        verify(productService, times(1)).decreaseStock(purchaseInfo.productId(), purchaseInfo.quantity());
        // - userSummaryService.recordPurchase() 호출 여부
        verify(userSummaryService, times(1)).recordPurchase(purchaseInfo.userId(), purchaseInfo.occurredAt());
        // - purchaseService.createPurchase() 호출 여부
        verify(purchaseService, times(1)).createPurchase(purchaseInfo);
    }

    @Test
    @DisplayName("선착순 구매(CAMPAIGNACTIVITY) 이벤트는 버퍼에 쌓여야 한다")
    void handle_CampaignPurchase_Buffered() {
        // 1. Given: CAMPAIGNACTIVITY 타입의 PurchaseInfoDto 생성
        PurchaseInfoDto purchaseInfo = new PurchaseInfoDto(
                1L,
                1L,
                1L,
                1L,
                Instant.now(),
                PurchaseType.CAMPAIGNACTIVITY,
                BigDecimal.valueOf(5000),
                1,
                Instant.now()
        );
        // 2. When: purchaseHandler.handle() 호출
        purchaseHandler.handle(purchaseInfo);
        // 3. Then: Mock 객체들의 행위 검증 (never) 및 버퍼 상태 확인
        // - 서비스 메서드들이 호출되지 않았는지 확인 (never)
        verify(productService, never()).decreaseStock(anyLong(), anyInt());
        verify(userSummaryService, never()).recordPurchase(anyLong(), any(Instant.class));
        verify(purchaseService, never()).createPurchase(any(PurchaseInfoDto.class));
        // - purchaseBuffer에 데이터가 들어갔는지 확인 (ReflectionTestUtils 사용 가능)
        ConcurrentLinkedQueue<?> purchaseBuffer = (ConcurrentLinkedQueue<?>) ReflectionTestUtils.getField(purchaseHandler, "purchaseBuffer");
        assertThat(purchaseBuffer).isNotEmpty();
    }

    @Test
    @DisplayName("Purchase batch 저장이 실패하면 개별 재시도로 폴백해야 한다")
    void flushBatch_WhenPurchaseBatchFails_FallsBackToIndividualRetry() {
        PurchaseInfoDto purchaseInfo = new PurchaseInfoDto(
                1L,
                1L,
                1L,
                1L,
                Instant.now(),
                PurchaseType.CAMPAIGNACTIVITY,
                BigDecimal.valueOf(5000),
                1,
                Instant.now()
        );
        PurchaseInfoDto secondPurchaseInfo = new PurchaseInfoDto(
                1L,
                1L,
                2L,
                1L,
                Instant.now(),
                PurchaseType.CAMPAIGNACTIVITY,
                BigDecimal.valueOf(5000),
                1,
                Instant.now()
        );

        purchaseHandler.handle(purchaseInfo);
        purchaseHandler.handle(secondPurchaseInfo);
        doThrow(new RuntimeException("batch failure"))
                .doNothing()
                .when(purchaseService).createPurchaseBatch(any());

        purchaseHandler.flushBatch();

        verify(purchaseService).createPurchaseBatch(argThat(purchases -> purchases.size() == 2));
        verify(purchaseService, times(2)).createPurchaseBatch(argThat(purchases -> purchases.size() == 1));
        verify(deadLetterHandler, never()).handle(any(), any());
    }

    @Test
    @DisplayName("UserSummary 갱신 실패는 이미 저장된 Purchase를 재시도하지 않아야 한다")
    void flushBatch_WhenUserSummaryUpdateFails_DoesNotReplayPurchase() {
        PurchaseInfoDto purchaseInfo = new PurchaseInfoDto(
                1L, 1L, 1L, 1L, Instant.now(), PurchaseType.CAMPAIGNACTIVITY,
                BigDecimal.valueOf(5000), 1, Instant.now());

        purchaseHandler.handle(purchaseInfo);
        doThrow(new RuntimeException("summary failure"))
                .when(userSummaryService).recordPurchaseBatch(anyMap());

        purchaseHandler.flushBatch();

        verify(purchaseService, times(1)).createPurchaseBatch(List.of(purchaseInfo));
        verify(deadLetterHandler, never()).handle(any(), any());
        verify(eventPublisher).publishEvent(any(CampaignActivityApprovedEvent.class));
    }

    @Test
    @DisplayName("개별 재시도까지 실패하면 DeadLetterHandler로 격리해야 한다")
    void flushBatch_WhenIndividualRetryFails_SendsToDeadLetterHandler() {
        PurchaseInfoDto purchaseInfo = new PurchaseInfoDto(
                1L,
                1L,
                1L,
                1L,
                Instant.now(),
                PurchaseType.CAMPAIGNACTIVITY,
                BigDecimal.valueOf(5000),
                1,
                Instant.now()
        );

        purchaseHandler.handle(purchaseInfo);
        doThrow(new RuntimeException("batch failure"))
                .doThrow(new RuntimeException("single failure"))
                .when(purchaseService).createPurchaseBatch(any());

        purchaseHandler.flushBatch();

        verify(deadLetterHandler).handle(eq(purchaseInfo), any(RuntimeException.class));
    }
}
