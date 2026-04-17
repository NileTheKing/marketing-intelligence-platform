package com.axon.core_service.service.scheduler;

import com.axon.core_service.domain.campaignactivity.CampaignActivity;
import com.axon.core_service.domain.dto.campaignactivity.CampaignActivityStatus;
import com.axon.core_service.repository.CampaignActivityRepository;
import com.axon.core_service.repository.PurchaseRepository;
import com.axon.core_service.service.ProductService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
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

    @InjectMocks
    private CampaignStockSyncScheduler scheduler;

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

        // When
        scheduler.syncCampaignStockManually(campaignId);

        // Then
        // 1. 재고 차감은 무조건 DB 수치인 10으로 호출되어야 함
        verify(productService, times(1)).syncCampaignStock(eq(productId), eq(10L));
        
        // 2. 캠페인 상태가 ENDED로 업데이트 되어야 함
        verify(campaign, times(1)).updateStatus(CampaignActivityStatus.ENDED);
        verify(campaignActivityRepository, times(1)).save(campaign);
        
        System.out.println("✅ Audit Test Success: Redis(15) vs MySQL(10) -> Sycned with 10.");
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

        // When
        scheduler.syncCampaignStockManually(campaignId);

        // Then
        verify(productService, times(1)).syncCampaignStock(eq(productId), eq(5L));
        System.out.println("✅ Audit Test Success: Redis(null) vs MySQL(5) -> Sycned with 5.");
    }
}
