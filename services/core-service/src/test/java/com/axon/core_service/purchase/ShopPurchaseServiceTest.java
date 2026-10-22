package com.axon.core_service.purchase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

import com.axon.core_service.catalog.ProductRepository;
import com.axon.core_service.catalog.domain.Product;
import com.axon.core_service.coupon.CouponService;
import com.axon.core_service.domain.purchase.Purchase;
import com.axon.core_service.repository.PurchaseRepository;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ShopPurchaseServiceTest {
    @Mock private ProductRepository productRepository;
    @Mock private CouponService couponService;
    @Mock private PurchaseRepository purchaseRepository;
    @Mock private Product product;

    @Test
    void linksTheRecordedPurchaseToTheCouponUsedForCheckout() {
        when(productRepository.findByIdAndCampaignOnlyFalse(3L)).thenReturn(Optional.of(product));

        new ShopPurchaseService(productRepository, couponService, purchaseRepository).recordPaymentSuccess(1L, 3L, 9L, BigDecimal.valueOf(8_000));

        ArgumentCaptor<Purchase> purchase = ArgumentCaptor.forClass(Purchase.class);
        InOrder order = inOrder(couponService, purchaseRepository);
        order.verify(couponService).useCoupon(9L, 1L);
        order.verify(purchaseRepository).save(purchase.capture());
        assertThat(purchase.getValue().getUserCouponId()).isEqualTo(9L);
    }

}
