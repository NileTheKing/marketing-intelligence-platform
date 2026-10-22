package com.axon.core_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.axon.core_service.domain.product.Product;
import com.axon.core_service.domain.purchase.Purchase;
import com.axon.core_service.repository.CampaignActivityRepository;
import com.axon.core_service.repository.ProductRepository;
import com.axon.core_service.repository.PurchaseRepository;
import com.axon.core_service.repository.UserCouponRepository;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.SliceImpl;
import org.springframework.data.domain.Sort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StoreViewServiceTest {

    @Mock private CampaignActivityRepository campaignActivityRepository;
    @Mock private ProductRepository productRepository;
    @Mock private UserCouponRepository userCouponRepository;
    @Mock private PurchaseRepository purchaseRepository;
    @Mock private CouponService couponService;
    @Mock private Product product;

    @Test
    void linksTheRecordedPurchaseToTheCouponUsedForCheckout() {
        when(productRepository.findByIdAndCampaignOnlyFalse(3L)).thenReturn(Optional.of(product));

        service().recordPaymentSuccess(1L, 3L, 9L, BigDecimal.valueOf(8_000));

        ArgumentCaptor<Purchase> purchase = ArgumentCaptor.forClass(Purchase.class);
        InOrder order = inOrder(couponService, purchaseRepository);
        order.verify(couponService).useCoupon(9L, 1L);
        order.verify(purchaseRepository).save(purchase.capture());
        assertThat(purchase.getValue().getUserCouponId()).isEqualTo(9L);
    }

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
}
