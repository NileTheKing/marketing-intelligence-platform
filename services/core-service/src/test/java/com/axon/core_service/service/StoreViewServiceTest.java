package com.axon.core_service.service;

import com.axon.core_service.coupon.CouponService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.axon.core_service.catalog.domain.Product;
import com.axon.core_service.campaign.CampaignActivityRepository;
import com.axon.core_service.catalog.ProductRepository;
import com.axon.core_service.coupon.UserCouponRepository;
import com.axon.core_service.campaign.domain.CampaignActivity;
import com.axon.core_service.campaign.dto.CampaignActivityStatus;
import com.axon.core_service.repository.PurchaseRepository;
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
    @Mock private CouponService couponService;
    @Mock private PurchaseRepository purchaseRepository;
    @Mock private Product product;

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
    void homeRanksBySalesOrderAndLoadsEveryShopCategory() {
        when(purchaseRepository.findTopSellingProductIds(any(), any(), any(), any())).thenReturn(List.of());
        when(purchaseRepository.findTopSellingProductIds(any(), any(), isNull(), any()))
                .thenReturn(List.of(40L, 1L, 16L));
        Product rankingForty = rankingProduct(40L);
        Product rankingOne = rankingProduct(1L);
        Product rankingSixteen = rankingProduct(16L);
        when(productRepository.findAllByIdInAndCampaignOnlyFalse(List.of(40L, 1L, 16L)))
                .thenReturn(List.of(rankingOne, rankingSixteen, rankingForty));
        when(productRepository.findAllByIdInAndCampaignOnlyFalse(List.of())).thenReturn(List.of());

        var view = service().getMainShopViewData(null, 0);

        assertThat(view.getRankings().get("ALL")).extracting(StoreViewService.ProductDisplayDto::getId)
                .containsExactly(40L, 1L, 16L);
        assertThat(view.getRankings().keySet())
                .containsExactly("ALL", "DIGITAL", "FASHION", "BEAUTY", "LIVING", "FOOD");
        for (String category : StoreViewService.SHOP_CATEGORIES.keySet()) {
            verify(productRepository).findTop20ByCampaignOnlyFalseAndCategoryOrderByDiscountRateDescIdAsc(category);
        }
    }

    @Test
    void openRunsShowOpenThenUpcomingThenRecentlyEnded() {
        LocalDateTime now = LocalDateTime.now();
        CampaignActivity endedEarlier = fcfs(1L, CampaignActivityStatus.ENDED, now.minusDays(9), now.minusDays(5));
        CampaignActivity endedLater = fcfs(2L, CampaignActivityStatus.ENDED, now.minusDays(9), now.minusDays(1));
        CampaignActivity upcoming = fcfs(3L, CampaignActivityStatus.ACTIVE, now.plusHours(2), now.plusDays(1));
        CampaignActivity open = fcfs(4L, CampaignActivityStatus.ACTIVE, now.minusHours(1), now.plusHours(5));
        when(campaignActivityRepository.findAllByActivityTypeAndStatusIn(any(), any()))
                .thenReturn(List.of(endedEarlier, upcoming, endedLater, open));
        when(purchaseRepository.findTopSellingProductIds(any(), any(), any(), any())).thenReturn(List.of());

        var view = service().getMainShopViewData(null, 0);

        assertThat(view.getOpenRuns()).extracting(StoreViewService.OpenRunDisplayDto::getId)
                .containsExactly(4L, 3L, 2L);
        assertThat(view.getOpenRuns().get(0).getDiscountRate()).isEqualTo(40);
    }

    private CampaignActivity fcfs(Long id, CampaignActivityStatus status, LocalDateTime start, LocalDateTime end) {
        CampaignActivity activity = mock(CampaignActivity.class);
        org.mockito.Mockito.lenient().when(activity.getId()).thenReturn(id);
        org.mockito.Mockito.lenient().when(activity.getName()).thenReturn("run-" + id);
        org.mockito.Mockito.lenient().when(activity.getStatus()).thenReturn(status);
        org.mockito.Mockito.lenient().when(activity.getStartDate()).thenReturn(start);
        org.mockito.Mockito.lenient().when(activity.getEndDate()).thenReturn(end);
        org.mockito.Mockito.lenient().when(activity.getPrice()).thenReturn(new BigDecimal("60000"));
        org.mockito.Mockito.lenient().when(activity.getProduct())
                .thenReturn(new Product("p", 1L, new BigDecimal("100000"), "DIGITAL", "brand", 0));
        return activity;
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
                couponService, purchaseRepository);
    }
}
