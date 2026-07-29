package com.axon.core_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.axon.core_service.domain.product.Product;
import com.axon.core_service.repository.CampaignActivityRepository;
import com.axon.core_service.repository.ProductRepository;
import com.axon.core_service.repository.PurchaseRepository;
import com.axon.core_service.repository.UserCouponRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class StoreViewServiceTest {

    @Test
    void normalShopListingReadsOnlyProductsAllowedForNormalSale() {
        ProductRepository productRepository = mock(ProductRepository.class);
        Product normalProduct = new Product("normal", 10L, BigDecimal.TEN, "TECH");
        ReflectionTestUtils.setField(normalProduct, "id", 100L);
        when(productRepository.findAllByCampaignOnlyFalse()).thenReturn(List.of(normalProduct));
        StoreViewService service = service(productRepository);

        StoreViewService.MainShopViewData result = service.getMainShopViewData(null);

        assertThat(result.getTechDeals()).hasSize(1);
        verify(productRepository).findAllByCampaignOnlyFalse();
    }

    @Test
    void checkoutRejectsCampaignOnlyProductAtTheServiceBoundary() {
        ProductRepository productRepository = mock(ProductRepository.class);
        when(productRepository.findByIdAndCampaignOnlyFalse(1L)).thenReturn(Optional.empty());
        StoreViewService service = service(productRepository);

        assertThatThrownBy(() -> service.getCheckoutViewData(10L, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Product not found");
    }

    private StoreViewService service(ProductRepository productRepository) {
        return new StoreViewService(mock(CampaignActivityRepository.class), productRepository,
                mock(UserCouponRepository.class), mock(PurchaseRepository.class), mock(CouponService.class));
    }
}
