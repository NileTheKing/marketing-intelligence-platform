package com.axon.core_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.axon.core_service.domain.product.Product;
import com.axon.core_service.repository.CampaignActivityRepository;
import com.axon.core_service.repository.ProductRepository;
import com.axon.core_service.repository.PurchaseRepository;
import com.axon.core_service.repository.UserCouponRepository;
import com.axon.core_service.domain.coupon.Coupon;
import com.axon.core_service.domain.coupon.UserCoupon;
import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.SliceImpl;
import org.springframework.data.domain.Sort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StoreViewServiceTest {

    @Mock private CampaignActivityRepository campaignActivityRepository;
    @Mock private ProductRepository productRepository;
    @Mock private UserCouponRepository userCouponRepository;
    @Mock private PurchaseRepository purchaseRepository;
    @Mock private CouponService couponService;


    @Test
    void productListUsesCaseInsensitiveCategoryAndStablePageAndReturnsMoneyAsNumbers() {
        Product first = new Product("first", 4L, new BigDecimal("100.00"), "TECH", "Brand", 10);
        Product second = new Product("second", 3L, new BigDecimal("25.00"), "TECH", null, null);
        when(productRepository.findByCampaignOnlyFalseAndCategoryIgnoreCase(org.mockito.ArgumentMatchers.eq("tech"), any()))
                .thenReturn(new SliceImpl<>(List.of(first, second), PageRequest.of(2, 2), true));

        var result = service().getProductList(" tech ", 2, 2);

        assertThat(result.page()).isEqualTo(2);
        assertThat(result.size()).isEqualTo(2);
        assertThat(result.hasNext()).isTrue();
        assertThat(result.items()).hasSize(2);
        assertThat(result.items().get(0).sellingPrice()).isEqualByComparingTo("90.00");
        assertThat(result.items().get(0).originalPrice()).isEqualByComparingTo("100.00");
        assertThat(result.items().get(0).brand()).isEqualTo("Brand");

        ArgumentCaptor<org.springframework.data.domain.Pageable> pageable =
                ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);
        verify(productRepository).findByCampaignOnlyFalseAndCategoryIgnoreCase(org.mockito.ArgumentMatchers.eq("tech"), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(2);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(2);
        assertThat(pageable.getValue().getSort()).isEqualTo(Sort.by(Sort.Direction.ASC, "id"));
    }

    @Test
    void productListRejectsInvalidPageAndSize() {
        assertThatThrownBy(() -> service().getProductList(null, -1, 20))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service().getProductList(null, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service().getProductList(null, 0, 101))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void homeLoadsFourLimitedCategoriesAndPreservesTheConfiguredRankingOrder() {
        Product rankingForty = rankingProduct(40L);
        Product rankingOne = rankingProduct(1L);
        Product rankingSixteen = rankingProduct(16L);
        when(productRepository.findAllByIdInAndCampaignOnlyFalse(List.of(1L, 16L, 31L, 46L, 40L)))
                .thenReturn(List.of(rankingForty, rankingSixteen, rankingOne));
        for (String category : List.of("TECH", "FOOD", "HOME", "FASHION")) {
            when(productRepository.findByCampaignOnlyFalseAndCategoryIgnoreCase(
                    org.mockito.ArgumentMatchers.eq(category), any()))
                    .thenReturn(new SliceImpl<>(List.of(), PageRequest.of(0, 4), false));
        }

        var view = service().getMainShopViewData(null, 0);

        assertThat(view.getRankings()).extracting(StoreViewService.ProductDisplayDto::getId)
                .containsExactly(1L, 16L, 40L);
        verify(productRepository, org.mockito.Mockito.never()).findByCampaignOnlyFalse(any());
        for (String category : List.of("TECH", "FOOD", "HOME", "FASHION")) {
            verify(productRepository).findByCampaignOnlyFalseAndCategoryIgnoreCase(
                    org.mockito.ArgumentMatchers.eq(category), any());
        }
    }

    private Product rankingProduct(Long id) {
        Product product = mock(Product.class);
        when(product.getId()).thenReturn(id);
        when(product.getProductName()).thenReturn("product-" + id);
        when(product.getPrice()).thenReturn(BigDecimal.TEN);
        when(product.getBrand()).thenReturn("brand");
        when(product.getCategory()).thenReturn("TECH");
        return product;
    }

    private StoreViewService service() {
        return new StoreViewService(campaignActivityRepository, productRepository, userCouponRepository,
                purchaseRepository, couponService);
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
