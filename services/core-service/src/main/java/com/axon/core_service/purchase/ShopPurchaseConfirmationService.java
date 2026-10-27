package com.axon.core_service.purchase;

import com.axon.core_service.catalog.ProductRepository;
import com.axon.core_service.coupon.CouponService;
import com.axon.core_service.domain.purchase.Purchase;
import com.axon.core_service.domain.purchase.PurchaseType;
import com.axon.core_service.repository.PurchaseRepository;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ShopPurchaseConfirmationService {
    private final ShopPurchaseRequestRepository requests;
    private final ProductRepository products;
    private final CouponService coupons;
    private final PurchaseRepository purchases;

    @Transactional
    public ShopPurchaseResponse confirm(Long userId, ShopPurchaseRequest request) {
        var record = new ShopPurchaseRequestRecord(userId, request);
        try {
            requests.saveAndFlush(record);
        } catch (DataIntegrityViolationException failure) {
            throw new RequestInsertFailure(failure);
        }
        var product = products.findByIdAndCampaignOnlyFalse(request.productId())
                .orElseThrow(() -> new ShopPurchaseException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND"));
        BigDecimal sellingPrice = ShopPriceCalculator.sellingPrice(product);
        BigDecimal discount = BigDecimal.ZERO;
        if (request.userCouponId() != null) {
            try {
                discount = coupons.useCoupon(request.userCouponId(), userId, product.getPrice(), product.getCategory());
            } catch (CouponService.InvalidCouponException failure) {
                throw new ShopPurchaseException(HttpStatus.BAD_REQUEST, "INVALID_COUPON");
            } catch (CouponService.CouponNotApplicableException failure) {
                throw new ShopPurchaseException(HttpStatus.BAD_REQUEST, "COUPON_NOT_APPLICABLE");
            } catch (CouponService.CouponAlreadyUsedException failure) {
                throw new ShopPurchaseException(HttpStatus.CONFLICT, "COUPON_ALREADY_USED");
            }
        }
        var purchase = purchases.saveAndFlush(Purchase.builder().userId(userId).productId(product.getId())
                .purchaseType(PurchaseType.SHOP).price(ShopPriceCalculator.paidAmount(sellingPrice, discount))
                .quantity(1).purchasedAt(Instant.now()).userCouponId(request.userCouponId()).build());
        record.complete(purchase.getId());
        return ShopPurchaseResponse.from(purchase);
    }

    static class RequestInsertFailure extends RuntimeException {
        RequestInsertFailure(DataIntegrityViolationException cause) { super(cause); }
    }
}
