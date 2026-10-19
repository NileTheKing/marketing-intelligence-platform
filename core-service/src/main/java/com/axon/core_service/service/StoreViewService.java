package com.axon.core_service.service;

import com.axon.core_service.domain.campaignactivity.CampaignActivity;
import com.axon.core_service.domain.campaignactivity.CouponDeliveryMode;
import com.axon.core_service.domain.coupon.CouponStatus;
import com.axon.core_service.domain.coupon.UserCoupon;
import com.axon.core_service.domain.dto.campaignactivity.CampaignActivityStatus;
import com.axon.core_service.domain.dto.campaignactivity.filter.FilterDetail;
import com.axon.core_service.domain.dto.coupon.ApplicableCouponDto;
import com.axon.core_service.domain.dto.product.ProductListItemDto;
import com.axon.core_service.domain.dto.product.ProductListResponse;
import com.axon.core_service.domain.product.Product;
import com.axon.core_service.domain.purchase.Purchase;
import com.axon.core_service.domain.purchase.PurchaseType;
import com.axon.core_service.exception.CampaignActivityNotFoundException;
import com.axon.core_service.exception.InvalidRequestException;
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
import java.util.Map;
import java.util.function.Function;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;

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

    @Transactional(readOnly = true)
    public MainShopViewData getMainShopViewData(String category, int page) {
        String normalizedCategory = normalizeCategory(category);
        if (normalizedCategory != null) {
            ProductListResponse products = getProductList(normalizedCategory, page, 20);
            return MainShopViewData.builder()
                    .selectedCategory(normalizedCategory)
                    .categoryProducts(products.items().stream().map(this::toDisplayDto).toList())
                    .page(products.page())
                    .hasNext(products.hasNext())
                    .build();
        }

        Map<Long, ProductDisplayDto> ranked = productRepository
                .findAllByIdInAndCampaignOnlyFalse(RANKING_PRODUCT_IDS).stream()
                .map(this::convertToProductDto)
                .collect(Collectors.toMap(ProductDisplayDto::getId, Function.identity()));
        List<ProductDisplayDto> rankings = RANKING_PRODUCT_IDS.stream()
                .map(ranked::get)
                .filter(java.util.Objects::nonNull)
                .toList();

        return MainShopViewData.builder()
                .rankings(rankings)
                .techDeals(homeCategoryProducts("TECH"))
                .foodDeals(homeCategoryProducts("FOOD"))
                .homeDeals(homeCategoryProducts("HOME"))
                .fashionDeals(homeCategoryProducts("FASHION"))
                .build();
    }

    @Transactional(readOnly = true)
    public ProductListResponse getProductList(String category, int page, int size) {
        if (page < 0) {
            throw new InvalidRequestException("page must be zero or greater");
        }
        if (size < 1 || size > 100) {
            throw new InvalidRequestException("size must be between 1 and 100");
        }

        String normalizedCategory = normalizeCategory(category);
        Slice<Product> products = productSlice(normalizedCategory, page, size);
        return new ProductListResponse(
                products.getContent().stream().map(this::toProductListItem).toList(),
                page,
                size,
                products.hasNext());
    }

    private Slice<Product> productSlice(String category, int page, int size) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "id"));
        return category == null
                ? productRepository.findByCampaignOnlyFalse(pageable)
                : productRepository.findByCampaignOnlyFalseAndCategoryIgnoreCase(category, pageable);
    }

    private List<ProductDisplayDto> homeCategoryProducts(String category) {
        return productSlice(category, 0, 4).getContent().stream()
                .map(this::convertToProductDto)
                .toList();
    }

    private String normalizeCategory(String category) {
        if (category == null || category.isBlank()) {
            return null;
        }
        return category.trim();
    }

    private ProductListItemDto toProductListItem(Product product) {
        BigDecimal originalPrice = product.getPrice();
        BigDecimal sellingPrice = discountedPrice(product);
        String imagePath = product.getImageUrl();
        if (imagePath != null && !imagePath.startsWith("/image/product/") && !imagePath.startsWith("/")) {
            imagePath = "/image/product/" + imagePath;
        }
        return new ProductListItemDto(
                product.getId(), product.getProductName(),
                product.getBrand() != null ? product.getBrand() : "SKU STORE",
                product.getCategory(), imagePath, originalPrice, sellingPrice,
                product.getDiscountRate() != null ? product.getDiscountRate() : 0);
    }

    private ProductDisplayDto toDisplayDto(ProductListItemDto item) {
        NumberFormat currency = NumberFormat.getInstance(Locale.KOREA);
        return ProductDisplayDto.builder()
                .id(item.id()).name(item.name()).brand(item.brand())
                .price(currency.format(item.sellingPrice())).rawPrice(item.sellingPrice())
                .originalPrice(currency.format(item.originalPrice())).discountRate(item.discountRate())
                .imageUrl(item.imageUrl()).category(item.category()).reviewCount(100).build();
    }

    @Transactional(readOnly = true)
    public ProductDisplayDto getProductDisplay(Long productId) {
        Product product = productRepository.findByIdAndCampaignOnlyFalse(productId)
                .orElseThrow(() -> new IllegalArgumentException("Product not found: " + productId));
        return convertToProductDto(product);
    }

    @Transactional(readOnly = true)
    public CheckoutViewData getCheckoutViewData(Long userId, Long productId) {
        Product product = productRepository.findByIdAndCampaignOnlyFalse(productId)
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

    @Transactional(readOnly = true)
    public CampaignActivitiesViewData getCampaignActivitiesViewData(Long userId) {
        List<CampaignActivityDisplayDto> allActivities = campaignActivityRepository
                .findAllByStatus(CampaignActivityStatus.ACTIVE)
                .stream()
                .filter(this::isDirectCouponOffer)
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

    @Transactional(readOnly = true)
    public CampaignActivityDisplayDto getActiveCampaignActivity(Long id) {
        CampaignActivity campaignActivity = campaignActivityRepository.findWithProductAndCouponById(id)
                .orElseThrow(() -> new CampaignActivityNotFoundException(id));

        if (campaignActivity.getStatus() != CampaignActivityStatus.ACTIVE) {
            return null;
        }
        if (!isDirectCouponOffer(campaignActivity)) {
            return null;
        }

        return convertToCampaignActivityDto(campaignActivity);
    }

    private boolean isDirectCouponOffer(CampaignActivity activity) {
        return activity.getActivityType() != CampaignActivityType.COUPON
                || activity.getCouponDeliveryMode() == null
                || activity.getCouponDeliveryMode() == CouponDeliveryMode.DIRECT;
    }

    @Transactional
    public void recordPaymentSuccess(Long userId, Long productId, Long userCouponId, BigDecimal finalPrice) {
        if (userId != null && productId != null && finalPrice != null) {
            productRepository.findByIdAndCampaignOnlyFalse(productId)
                    .orElseThrow(() -> new IllegalArgumentException("Campaign-only products cannot use shop checkout"));
            if (userCouponId != null) {
                couponService.useCoupon(userCouponId, userId);
                log.info("User coupon {} successfully used by user {} during payment", userCouponId, userId);
            }
            Purchase purchase = Purchase.builder()
                    .userId(userId)
                    .productId(productId)
                    .campaignActivityId(null)
                    .purchaseType(PurchaseType.SHOP)
                    .price(finalPrice)
                    .quantity(1)
                    .purchasedAt(Instant.now())
                    .userCouponId(userCouponId)
                    .build();
            purchaseRepository.save(purchase);
            log.info("Purchase saved: userId={}, productId={}, price={}", userId, productId, finalPrice);
        }
    }

    @Transactional(readOnly = true)
    public List<UserCouponDisplayDto> getValidUserCoupons(Long userId) {
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
                .map(this::convertToUserCouponDto)
                .collect(Collectors.toList());
    }

    private UserCouponDisplayDto convertToUserCouponDto(UserCoupon userCoupon) {
        var coupon = userCoupon.getCoupon();
        return UserCouponDisplayDto.builder()
                .couponName(coupon.getCouponName())
                .discountAmount(coupon.getDiscountAmount())
                .discountRate(coupon.getDiscountRate())
                .minOrderAmount(coupon.getMinOrderAmount())
                .targetCategory(coupon.getTargetCategory())
                .endDate(coupon.getEndDate())
                .build();
    }

    private CampaignActivityDisplayDto safeConvertToCampaignActivityDto(CampaignActivity activity) {
        try {
            return convertToCampaignActivityDto(activity);
        } catch (Exception e) {
            log.warn("Failed to convert CampaignActivity id={}: {}", activity.getId(), e.getMessage());
            return null;
        }
    }

    private ProductDisplayDto convertToProductDto(Product product) {
        NumberFormat currency = NumberFormat.getInstance(Locale.KOREA);

        BigDecimal originalPrice = product.getPrice();
        BigDecimal sellingPrice = discountedPrice(product);

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

    private BigDecimal discountedPrice(Product product) {
        BigDecimal originalPrice = product.getPrice();
        if (product.getDiscountRate() == null || product.getDiscountRate() <= 0) {
            return originalPrice;
        }
        BigDecimal discount = originalPrice.multiply(BigDecimal.valueOf(product.getDiscountRate()))
                .divide(BigDecimal.valueOf(100));
        return originalPrice.subtract(discount);
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
        private int page;
        private boolean hasNext;
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

    @Getter
    @Builder
    public static class UserCouponDisplayDto {
        private String couponName;
        private BigDecimal discountAmount;
        private Integer discountRate;
        private BigDecimal minOrderAmount;
        private String targetCategory;
        private LocalDateTime endDate;
    }
}
