package com.axon.core_service.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.axon.core_service.domain.campaign.Campaign;
import com.axon.core_service.domain.dto.campaignactivity.CampaignActivityRequest;
import com.axon.core_service.domain.dto.campaignactivity.CampaignActivityStatus;
import com.axon.core_service.domain.product.Product;
import com.axon.core_service.repository.CampaignActivityEntryRepository;
import com.axon.core_service.repository.CampaignActivityRepository;
import com.axon.core_service.repository.CampaignRepository;
import com.axon.core_service.repository.CouponRepository;
import com.axon.core_service.repository.ProductRepository;
import com.axon.messaging.CampaignActivityType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

class CampaignActivityProductPolicyTest {

    @Test
    void activeFcfsRejectsAProductThatIsAlsoSellableInTheNormalShop() {
        CampaignRepository campaignRepository = mock(CampaignRepository.class);
        ProductRepository productRepository = mock(ProductRepository.class);
        CampaignActivityService service = service(campaignRepository, productRepository, mock(CampaignActivityRepository.class));
        Product normalProduct = new Product("normal", 10L, BigDecimal.TEN, "TECH", null, null, false);
        when(campaignRepository.findById(1L)).thenReturn(Optional.of(mock(Campaign.class)));
        when(productRepository.findById(2L)).thenReturn(Optional.of(normalProduct));

        assertThatThrownBy(() -> service.createCampaignActivity(1L, activeFcfsRequest(2L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("campaign-only");
    }

    @Test
    void activeFcfsRejectsASecondActivityUsingTheSameDedicatedProduct() {
        CampaignRepository campaignRepository = mock(CampaignRepository.class);
        ProductRepository productRepository = mock(ProductRepository.class);
        CampaignActivityRepository activityRepository = mock(CampaignActivityRepository.class);
        CampaignActivityService service = service(campaignRepository, productRepository, activityRepository);
        Product campaignProduct = new Product("campaign", 10L, BigDecimal.TEN, "TECH", null, null, true);
        ReflectionTestUtils.setField(campaignProduct, "id", 2L);
        when(campaignRepository.findById(1L)).thenReturn(Optional.of(mock(Campaign.class)));
        when(productRepository.findById(2L)).thenReturn(Optional.of(campaignProduct));
        when(activityRepository.existsByProduct_IdAndStatusAndActivityType(2L,
                CampaignActivityStatus.ACTIVE, CampaignActivityType.FIRST_COME_FIRST_SERVE)).thenReturn(true);

        assertThatThrownBy(() -> service.createCampaignActivity(1L, activeFcfsRequest(2L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("only one ACTIVE");
    }

    private CampaignActivityService service(CampaignRepository campaignRepository, ProductRepository productRepository,
            CampaignActivityRepository activityRepository) {
        return new CampaignActivityService(campaignRepository, activityRepository,
                mock(CampaignActivityEntryRepository.class), productRepository, mock(CouponRepository.class),
                mock(StringRedisTemplate.class));
    }

    private CampaignActivityRequest activeFcfsRequest(Long productId) {
        return CampaignActivityRequest.builder()
                .name("FCFS")
                .limitCount(10)
                .status(CampaignActivityStatus.ACTIVE)
                .startDate(LocalDateTime.now())
                .endDate(LocalDateTime.now().plusDays(1))
                .activityType(CampaignActivityType.FIRST_COME_FIRST_SERVE)
                .productId(productId)
                .price(BigDecimal.TEN)
                .quantity(1)
                .build();
    }
}
