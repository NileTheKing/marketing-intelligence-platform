package com.axon.core_service.service.scheduler;

import com.axon.core_service.domain.campaignactivity.CampaignActivity;
import com.axon.core_service.domain.dto.campaignactivity.CampaignActivityStatus;
import com.axon.core_service.repository.CampaignActivityRepository;
import com.axon.core_service.repository.PurchaseRepository;
import com.axon.core_service.service.ProductService;
import com.axon.core_service.service.reconciliation.ReconciliationIssueService;
import com.axon.core_service.domain.purchase.PurchaseStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CampaignStockSyncSchedulerTest {

    @Mock
    private CampaignActivityRepository campaignActivityRepository;

    @Mock
    private PurchaseRepository purchaseRepository;

    @Mock
    private ProductService productService;

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private ReconciliationIssueService reconciliationIssueService;

    @Mock
    private TransactionTemplate transactionTemplate;

    @InjectMocks
    private CampaignStockSyncService syncService;

    @org.junit.jupiter.api.BeforeEach
    void executeTransactionCallbacks() {
        doAnswer(invocation -> {
            java.util.function.Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    @Test
    @DisplayName("정산 시 Redis와 MySQL 수치가 다르면, MySQL(SSOT) 기준으로 재고가 정산되어야 한다")
    void syncCampaignStock_withDiscrepancy_shouldUseMySQLCount() {
        // Given
        Long campaignId = 1L;
        Long productId = 100L;
        CampaignActivity campaign = mock(CampaignActivity.class);
        
        when(campaign.getId()).thenReturn(campaignId);
        when(campaign.getProductId()).thenReturn(productId);

        when(campaignActivityRepository.findById(campaignId)).thenReturn(Optional.of(campaign));
        
        // Redis는 15개 팔렸다고 주장 (Ghost Data 포함)
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("campaign:" + campaignId + ":counter")).thenReturn("15");
        
        // 하지만 실제 DB 결제 로그는 10개뿐임 (진실의 원천)
        when(purchaseRepository.countByCampaignActivityId(campaignId)).thenReturn(10L);
        when(purchaseRepository.countByCampaignActivityIdAndStatus(campaignId, PurchaseStatus.CONFIRMED)).thenReturn(10L);

        // When
        syncService.syncCampaignStockManually(campaignId);

        // Then
        // 1. 재고 차감은 무조건 DB 수치인 10으로 호출되어야 함
        verify(productService, times(1)).syncCampaignStock(eq(productId), eq(10L));
        
        // 2. 캠페인 상태가 ENDED로 업데이트 되어야 함
        verify(campaign, times(1)).updateStatus(CampaignActivityStatus.ENDED);
    }

    @Test
    @DisplayName("Redis 데이터가 유실되어도 MySQL 로그가 있다면 정상적으로 정산되어야 한다")
    void syncCampaignStock_withRedisLoss_shouldUseMySQLCount() {
        // Given
        Long campaignId = 2L;
        Long productId = 200L;
        CampaignActivity campaign = mock(CampaignActivity.class);
        
        when(campaign.getId()).thenReturn(campaignId);
        when(campaign.getProductId()).thenReturn(productId);

        when(campaignActivityRepository.findById(campaignId)).thenReturn(Optional.of(campaign));
        
        // Redis 데이터 유실 (null)
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("campaign:" + campaignId + ":counter")).thenReturn(null);
        
        // 실제 DB 결제 로그는 5개 존재
        when(purchaseRepository.countByCampaignActivityId(campaignId)).thenReturn(5L);
        when(purchaseRepository.countByCampaignActivityIdAndStatus(campaignId, PurchaseStatus.CONFIRMED)).thenReturn(5L);

        // When
        syncService.syncCampaignStockManually(campaignId);

        // Then
        verify(productService, times(1)).syncCampaignStock(eq(productId), eq(5L));
    }

    @Test
    void failureInOneActivityDoesNotPreventTheNextActivityTransaction() {
        CampaignActivity first = mock(CampaignActivity.class);
        CampaignActivity second = mock(CampaignActivity.class);
        when(campaignActivityRepository.findIdsByStatus(CampaignActivityStatus.ACTIVE))
                .thenReturn(List.of(1L, 2L));
        when(campaignActivityRepository.findById(1L)).thenReturn(Optional.of(first));
        when(campaignActivityRepository.findById(2L)).thenReturn(Optional.of(second));
        when(first.getId()).thenReturn(1L);
        when(first.getProductId()).thenReturn(100L);
        when(first.getSyncedCount()).thenReturn(0);
        when(second.getId()).thenReturn(2L);
        when(second.getProductId()).thenReturn(200L);
        when(second.getSyncedCount()).thenReturn(0);
        when(second.getEndDate()).thenReturn(LocalDateTime.now().plusDays(1));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("campaign:1:counter")).thenReturn("1");
        when(valueOperations.get("campaign:2:counter")).thenReturn("1");
        when(purchaseRepository.countByCampaignActivityId(any())).thenReturn(1L);
        when(purchaseRepository.countByCampaignActivityIdAndStatus(any(), eq(PurchaseStatus.CONFIRMED)))
                .thenReturn(1L);
        doThrow(new IllegalStateException("poison activity"))
                .when(productService).syncCampaignStock(100L, 1L);

        syncService.syncOngoingCampaignStocks();

        verify(transactionTemplate, times(2)).executeWithoutResult(any());
        verify(productService).syncCampaignStock(200L, 1L);
    }
}
