package com.axon.core_service.service;

import com.axon.core_service.domain.campaignactivity.CampaignActivity;
import com.axon.core_service.domain.coupon.CouponStatus;
import com.axon.core_service.domain.coupon.UserCoupon;
import com.axon.core_service.domain.dto.campaignactivity.CampaignActivityStatus;
import com.axon.core_service.domain.dto.campaignactivity.filter.FilterDetail;
import com.axon.core_service.domain.dto.coupon.ApplicableCouponDto;
import com.axon.core_service.domain.product.Product;
import com.axon.core_service.domain.purchase.Purchase;
import com.axon.core_service.domain.purchase.PurchaseType;
import com.axon.core_service.exception.CampaignActivityNotFoundException;
import com.axon.core_service.repository.CampaignActivityRepository;
import com.axon.core_service.repository.ProductRepository;
import com.axon.core_service.repository.PurchaseRepository;
import com.axon.core_service.repository.UserCouponRepository;
import com.axon.messaging.CampaignActivityType;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class StoreViewService {

    private static final List<Long> RANKING_PRODUCT_IDS = List.of(1L, 16L, 31L, 46L, 40L);

    private final CampaignActivityRepository campaignActivityRepository;
    private final ProductRepository productRepository;
    private final UserCouponRepository userCouponRepository;
    private final PurchaseRepository purchaseRepository;
    private final CouponService couponService;

    public MainShopViewData getMainShopViewData(String category) {
        List<ProductDisplayDto> allProducts = productRepository.findAll().stream()
                .map(this::convertToProductDto)
                .collect(Collectors.toList());

        List<ProductDisplayDto> rankings = allProducts.stream()
                .filter(product -> RANKING_PRODUCT_IDS.contains(product.getId()))
                .sorted((p1, p2) -> Integer.compare(
                        RANKING_PRODUCT_IDS.indexOf(p1.getId()),
                        RANKING_PRODUCT_IDS.indexOf(p2.getId())))
                .collect(Collectors.toList());

        if (category != null && !category.isEmpty()) {
            return MainShopViewData.builder()
                    .rankings(rankings)
                    .selectedCategory(category)
                    .categoryProducts(filterDtoByCategory(allProducts, category, Integer.MAX_VALUE))
                    .build();
        }

        return MainShopViewData.builder()
                .rankings(rankings)
                .techDeals(filterDtoByCategory(allProducts, "TECH", 4))
                .foodDeals(filterDtoByCategory(allProducts, "FOOD", 4))
                .homeDeals(filterDtoByCategory(allProducts, "HOME", 4))
                .fashionDeals(filterDtoByCategory(allProducts, "FASHION", 4))
                .build();
    }

    public ProductDisplayDto getProductDisplay(Long productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new IllegalArgumentException("Product not found: " + productId));
        return convertToProductDto(product);
    }

    public CheckoutViewData getCheckoutViewData(Long userId, Long productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new IllegalArgumentException("Product not found: " + productId));

        List<ApplicableCouponDto> coupons = List.of();
        if (userId != null) {
            try {
                coupons = couponService.getApplicableCoupons(userId, product.getPrice(), product.getCategory());
            } catch (Exception e) {
                log.error("Error loading coupons for checkout", e);
            }
        }

        return CheckoutViewData.builder()
                .product(convertToProductDto(product))
                .coupons(coupons)
                .build();
    }

    public CampaignActivitiesViewData getCampaignActivitiesViewData(Long userId) {
        List<CampaignActivityDisplayDto> allActivities = campaignActivityRepository
                .findAllByStatus(CampaignActivityStatus.ACTIVE)
                .stream()
                .map(this::safeConvertToCampaignActivityDto)
                .filter(dto -> dto != null)
                .collect(Collectors.toList());

        List<CampaignActivityDisplayDto> fcfsActivities = allActivities.stream()
                .filter(activity -> activity.getActivityType() != CampaignActivityType.COUPON)
                .collect(Collectors.toList());

        List<CampaignActivityDisplayDto> couponActivities = allActivities.stream()
                .filter(activity -> activity.getActivityType() == CampaignActivityType.COUPON)
                .collect(Collectors.toList());

        List<Long> issuedCouponIds = userId != null
                ? userCouponRepository.findAllCouponIdsByUserId(userId)
                : List.of();

        return CampaignActivitiesViewData.builder()
                .fcfsCampaignActivities(fcfsActivities)
                .raffleCampaignActivities(new ArrayList<>())
                .coupons(couponActivities)
                .issuedCouponIds(issuedCouponIds)
                .build();
    }

    public CampaignActivityDisplayDto getActiveCampaignActivity(Long id) {
        CampaignActivity campaignActivity = campaignActivityRepository.findWithProductAndCouponById(id)
                .orElseThrow(() -> new CampaignActivityNotFoundException(id));

        if (campaignActivity.getStatus() != CampaignActivityStatus.ACTIVE) {
            return null;
        }

        return convertToCampaignActivityDto(campaignActivity);
    }

    public void recordPaymentSuccess(Long userId, Long productId, Long couponId, BigDecimal finalPrice) {
        if (userId != null && productId != null && finalPrice != null) {
            Purchase purchase = Purchase.builder()
                    .userId(userId)
                    .productId(productId)
                    .campaignActivityId(null)
                    .purchaseType(PurchaseType.SHOP)
                    .price(finalPrice)
                    .quantity(1)
                    .purchasedAt(Instant.now())
                    .build();
            purchaseRepository.save(purchase);
            log.info("Purchase saved: userId={}, productId={}, price={}", userId, productId, finalPrice);
        }

        if (couponId != null && userId != null) {
            couponService.useCoupon(couponId, userId);
            log.info("Coupon {} successfully used by user {} during payment", couponId, userId);
        }
    }

    public List<UserCoupon> getValidUserCoupons(Long userId) {
        if (userId == null) {
            return List.of();
        }

        LocalDateTime now = LocalDateTime.now();
        return userCouponRepository.findAllByUserId(userId).stream()
                .filter(userCoupon -> userCoupon.getStatus() == CouponStatus.ISSUED)
                .filter(userCoupon -> {
                    var coupon = userCoupon.getCoupon();
                    return now.isAfter(coupon.getStartDate()) && now.isBefore(coupon.getEndDate());
                })
                .collect(Collectors.toList());
    }

    private CampaignActivityDisplayDto safeConvertToCampaignActivityDto(CampaignActivity activity) {
        try {
            return convertToCampaignActivityDto(activity);
        } catch (Exception e) {
            log.warn("Failed to convert CampaignActivity id={}: {}", activity.getId(), e.getMessage());
            return null;
        }
    }

    private List<ProductDisplayDto> filterDtoByCategory(List<ProductDisplayDto> list, String category, int limit) {
        return list.stream()
                .filter(product -> category.equalsIgnoreCase(product.getCategory()))
                .limit(limit)
                .collect(Collectors.toList());
    }

    private ProductDisplayDto convertToProductDto(Product product) {
        NumberFormat currency = NumberFormat.getInstance(Locale.KOREA);

        BigDecimal originalPrice = product.getPrice();
        BigDecimal sellingPrice = originalPrice;

        if (product.getDiscountRate() != null && product.getDiscountRate() > 0) {
            BigDecimal discount = originalPrice.multiply(BigDecimal.valueOf(product.getDiscountRate()))
                    .divide(BigDecimal.valueOf(100));
            sellingPrice = originalPrice.subtract(discount);
        }

        String imagePath = product.getImageUrl();
        if (imagePath != null && !imagePath.startsWith("/image/product/") && !imagePath.startsWith("/")) {
            imagePath = "/image/product/" + imagePath;
        }

        return ProductDisplayDto.builder()
                .id(product.getId())
                .name(product.getProductName())
                .brand(product.getBrand() != null ? product.getBrand() : "SKU STORE")
                .price(currency.format(sellingPrice))
                .rawPrice(sellingPrice)
                .originalPrice(currency.format(originalPrice))
                .discountRate(product.getDiscountRate() != null ? product.getDiscountRate() : 0)
                .imageUrl(imagePath)
                .category(product.getCategory())
                .reviewCount(100)
                .build();
    }

    private CampaignActivityDisplayDto convertToCampaignActivityDto(CampaignActivity activity) {
        NumberFormat currency = NumberFormat.getInstance(Locale.KOREA);

        BigDecimal originalPrice = activity.getProduct() != null
                ? activity.getProduct().getPrice()
                : activity.getPrice();

        String couponName = null;
        BigDecimal couponDiscountAmount = null;
        Integer couponDiscountRate = null;

        if (activity.getActivityType() == CampaignActivityType.COUPON && activity.getCoupon() != null) {
            couponName = activity.getCoupon().getCouponName();
            couponDiscountAmount = activity.getCoupon().getDiscountAmount();
            couponDiscountRate = activity.getCoupon().getDiscountRate();
        }

        return CampaignActivityDisplayDto.builder()
                .id(activity.getId())
                .title(activity.getName())
                .description(activity.getActivityType() == CampaignActivityType.COUPON
                        ? "쿠폰 다운로드 이벤트"
                        : "선착순 한정 판매")
                .price(currency.format(activity.getPrice()))
                .originalPrice(currency.format(originalPrice))
                .limitCount(activity.getLimitCount() != null ? activity.getLimitCount() : 0)
                .imageUrl(activity.getImageUrl())
                .startDate(activity.getStartDate())
                .endDate(activity.getEndDate())
                .filters(activity.getFilters())
                .activityType(activity.getActivityType())
                .productId(activity.getProductId())
                .couponId(activity.getCoupon() != null ? activity.getCoupon().getId() : null)
                .couponName(couponName)
                .couponDiscountAmount(couponDiscountAmount)
                .couponDiscountRate(couponDiscountRate)
                .build();
    }

    @Getter
    @Builder
    public static class MainShopViewData {
        private List<ProductDisplayDto> rankings;
        private String selectedCategory;
        private List<ProductDisplayDto> categoryProducts;
        private List<ProductDisplayDto> techDeals;
        private List<ProductDisplayDto> foodDeals;
        private List<ProductDisplayDto> homeDeals;
        private List<ProductDisplayDto> fashionDeals;
    }

    @Getter
    @Builder
    public static class CheckoutViewData {
        private ProductDisplayDto product;
        private List<ApplicableCouponDto> coupons;
    }

    @Getter
    @Builder
    public static class CampaignActivitiesViewData {
        private List<CampaignActivityDisplayDto> fcfsCampaignActivities;
        private List<CampaignActivityDisplayDto> raffleCampaignActivities;
        private List<CampaignActivityDisplayDto> coupons;
        private List<Long> issuedCouponIds;
    }

    @Getter
    @Builder
    public static class CampaignActivityDisplayDto {
        private Long id;
        private String title;
        private String description;
        private String price;
        private String originalPrice;
        private int limitCount;
        private String imageUrl;
        private LocalDateTime startDate;
        private LocalDateTime endDate;
        private List<FilterDetail> filters;
        private CampaignActivityType activityType;
        private Long productId;
        private Long couponId;
        private String couponName;
        private BigDecimal couponDiscountAmount;
        private Integer couponDiscountRate;
    }

    @Getter
    @Builder
    public static class ProductDisplayDto {
        private Long id;
        private String name;
        private String brand;
        private String price;
        private BigDecimal rawPrice;
        private String originalPrice;
        private int discountRate;
        private String imageUrl;
        private String category;
        private int reviewCount;
    }
}
