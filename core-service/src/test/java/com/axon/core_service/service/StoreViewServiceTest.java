package com.axon.core_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.axon.core_service.domain.product.Product;
import com.axon.core_service.domain.coupon.Coupon;
import com.axon.core_service.domain.coupon.UserCoupon;
import com.axon.core_service.repository.CampaignActivityRepository;
import com.axon.core_service.repository.ProductRepository;
import com.axon.core_service.repository.PurchaseRepository;
import com.axon.core_service.repository.UserCouponRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
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

    @Test
    void mypageConvertsCouponEntitiesToDisplayDtosBeforeReturning() {
        UserCouponRepository userCouponRepository = mock(UserCouponRepository.class);
        Coupon coupon = Coupon.builder()
                .name("Welcome")
                .discountAmount(BigDecimal.valueOf(3_000))
                .startDate(LocalDateTime.now().minusDays(1))
                .endDate(LocalDateTime.now().plusDays(1))
                .build();
        when(userCouponRepository.findAllByUserId(10L)).thenReturn(List.of(UserCoupon.builder()
                .userId(10L)
                .coupon(coupon)
                .build()));
        StoreViewService service = new StoreViewService(mock(CampaignActivityRepository.class),
                mock(ProductRepository.class), userCouponRepository, mock(PurchaseRepository.class),
                mock(CouponService.class));

        List<StoreViewService.UserCouponDisplayDto> result = service.getValidUserCoupons(10L);

        assertThat(result).singleElement().satisfies(display -> {
            assertThat(display.getCouponName()).isEqualTo("Welcome");
            assertThat(display.getDiscountAmount()).isEqualByComparingTo("3000");
        });
    }

    private StoreViewService service(ProductRepository productRepository) {
        return new StoreViewService(mock(CampaignActivityRepository.class), productRepository,
                mock(UserCouponRepository.class), mock(PurchaseRepository.class), mock(CouponService.class));
    }
}
