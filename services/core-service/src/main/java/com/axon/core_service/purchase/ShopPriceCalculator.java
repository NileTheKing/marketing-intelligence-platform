package com.axon.core_service.purchase;

import com.axon.core_service.catalog.domain.Product;
import java.math.BigDecimal;
import java.math.RoundingMode;

public final class ShopPriceCalculator {
    private ShopPriceCalculator() { }

    public static BigDecimal sellingPrice(Product product) {
        BigDecimal price = product.getPrice();
        int rate = product.getDiscountRate() == null ? 0 : product.getDiscountRate();
        if (price == null || price.signum() < 0 || rate < 0 || rate > 100) {
            throw new IllegalStateException("Invalid product price or discount rate");
        }
        return price.subtract(price.multiply(BigDecimal.valueOf(rate)).divide(BigDecimal.valueOf(100)));
    }

    public static BigDecimal paidAmount(BigDecimal sellingPrice, BigDecimal couponDiscount) {
        if (couponDiscount.signum() < 0) throw new IllegalStateException("Invalid coupon discount");
        BigDecimal result = sellingPrice.subtract(couponDiscount.min(sellingPrice));
        // purchases.price is DECIMAL(10,2): make the value returned now equal to the stored value.
        result = result.setScale(2, RoundingMode.HALF_UP);
        if (result.precision() > 10) throw new IllegalStateException("Purchase amount exceeds storage precision");
        return result;
    }
}
