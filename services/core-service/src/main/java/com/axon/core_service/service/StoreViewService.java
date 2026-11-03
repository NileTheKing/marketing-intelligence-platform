package com.axon.core_service.service;

import com.axon.core_service.coupon.CouponService;

import com.axon.core_service.campaign.domain.CampaignActivity;
import com.axon.core_service.campaign.domain.CouponDeliveryMode;
import com.axon.core_service.coupon.domain.CouponStatus;
import com.axon.core_service.coupon.domain.UserCoupon;
import com.axon.core_service.campaign.dto.CampaignActivityStatus;
import com.axon.core_service.campaign.dto.filter.FilterDetail;
import com.axon.core_service.coupon.dto.ApplicableCouponDto;
import com.axon.core_service.catalog.dto.ProductListItemDto;
import com.axon.core_service.catalog.dto.ProductListResponse;
import com.axon.core_service.catalog.domain.Product;
import com.axon.core_service.exception.CampaignActivityNotFoundException;
import com.axon.core_service.exception.InvalidRequestException;
import com.axon.core_service.campaign.CampaignActivityRepository;
import com.axon.core_service.catalog.ProductRepository;
import com.axon.core_service.domain.purchase.PurchaseStatus;
import com.axon.core_service.repository.PurchaseRepository;
import com.axon.core_service.coupon.UserCouponRepository;
import com.axon.messaging.CampaignActivityType;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
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

    public static final Map<String, String> SHOP_CATEGORIES = categoryLabels();
    private static final int RANKING_DAYS = 30;
    private static final int RANKING_SIZE = 5;
    private static final int DEAL_SIZE = 4;
    private static final int OPEN_RUN_SIZE = 3;
    private static final int OPEN_RUN_PAGE_SIZE = 24;

    private final CampaignActivityRepository campaignActivityRepository;
    private final ProductRepository productRepository;
    private final UserCouponRepository userCouponRepository;
    private final CouponService couponService;
    private final PurchaseRepository purchaseRepository;

    @Transactional(readOnly = true)
    public MainShopViewData getMainShopViewData(String category, int page) {
        String normalizedCategory = normalizeCategory(category);
        if (normalizedCategory != null) {
            ProductListResponse products = getProductList(normalizedCategory, page, 20);
            return MainShopViewData.builder()
                    .selectedCategory(normalizedCategory)
                    .selectedCategoryLabel(SHOP_CATEGORIES.getOrDefault(normalizedCategory.toUpperCase(), normalizedCategory))
                    .categoryProducts(products.items().stream().map(this::toDisplayDto).toList())
                    .page(products.page())
                    .hasNext(products.hasNext())
                    .build();
        }

        Map<String, List<ProductDisplayDto>> rankings = new LinkedHashMap<>();
        rankings.put("ALL", topSellingProducts(null));
        Map<String, List<ProductDisplayDto>> categoryDeals = new LinkedHashMap<>();
        for (String key : SHOP_CATEGORIES.keySet()) {
            rankings.put(key, topSellingProducts(key));
            categoryDeals.put(key, productRepository
                    .findTop20ByCampaignOnlyFalseAndCategoryOrderByDiscountRateDescIdAsc(key).stream()
                    .map(this::convertToProductDto)
                    .filter(distinctPhoto())
                    .limit(DEAL_SIZE)
                    .toList());
        }

        return MainShopViewData.builder()
                .rankings(rankings)
                .rankingDays(RANKING_DAYS)
                .categoryDeals(categoryDeals)
                .openRuns(openRuns(OPEN_RUN_SIZE))
                .build();
    }

    // 같은 상태 안에서는 진행 중과 예정은 시작 시각 순, 마감은 최근 마감 순
    private static int compareWithinState(OpenRunDisplayDto a, OpenRunDisplayDto b) {
        return a.getState() == OpenRunState.ENDED
                ? b.getEndDate().compareTo(a.getEndDate())
                : a.getStartDate().compareTo(b.getStartDate());
    }

    private OpenRunDisplayDto toOpenRun(CampaignActivity activity, LocalDateTime now) {
        OpenRunState state;
        if (activity.getStatus() == CampaignActivityStatus.ENDED || activity.getEndDate().isBefore(now)) {
            state = OpenRunState.ENDED;
        } else if (activity.getStartDate().isAfter(now)) {
            state = OpenRunState.UPCOMING;
        } else {
            state = OpenRunState.OPEN;
        }
        BigDecimal listPrice = activity.getProduct() != null ? activity.getProduct().getPrice() : activity.getPrice();
        int discountRate = listPrice.signum() > 0
                ? listPrice.subtract(activity.getPrice()).multiply(BigDecimal.valueOf(100))
                        .divide(listPrice, 0, java.math.RoundingMode.DOWN).intValue()
                : 0;
        return OpenRunDisplayDto.builder()
                .id(activity.getId())
                .title(activity.getName())
                .imageUrl(activity.getImageUrl())
                .price(NumberFormat.getInstance(Locale.KOREA).format(activity.getPrice()))
                .discountRate(Math.max(discountRate, 0))
                .limitCount(activity.getLimitCount() != null ? activity.getLimitCount() : 0)
                .startDate(activity.getStartDate())
                .endDate(activity.getEndDate())
                .state(state)
                .build();
    }

    private static Map<String, String> categoryLabels() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("DIGITAL", "디지털");
        labels.put("FASHION", "패션");
        labels.put("BEAUTY", "뷰티");
        labels.put("LIVING", "리빙");
        labels.put("FOOD", "푸드");
        return java.util.Collections.unmodifiableMap(labels);
    }

    // 최근 판매 수량 순. 구매 기록이 없는 기간이면 빈 목록이 된다.
    private List<ProductDisplayDto> topSellingProducts(String category) {
        List<Long> ids = purchaseRepository.findTopSellingProductIds(PurchaseStatus.CONFIRMED,
                LocalDateTime.now().minusDays(RANKING_DAYS), category, PageRequest.of(0, RANKING_SIZE * 4));
        Map<Long, ProductDisplayDto> products = productRepository.findAllByIdInAndCampaignOnlyFalse(ids).stream()
                .map(this::convertToProductDto)
                .collect(Collectors.toMap(ProductDisplayDto::getId, Function.identity()));
        return ids.stream().map(products::get).filter(java.util.Objects::nonNull)
                .filter(distinctPhoto())
                .limit(RANKING_SIZE)
                .toList();
    }

    // 같은 품목은 사진을 같이 쓰므로, 메인 목록에서는 사진이 겹치는 상품을 한 번만 보여준다.
    private static java.util.function.Predicate<ProductDisplayDto> distinctPhoto() {
        java.util.Set<String> seen = new java.util.HashSet<>();
        return p -> p.getImageUrl() == null || seen.add(p.getImageUrl());
    }

    // 진행 중, 오픈 예정, 최근 마감 순으로 선착순 판매를 보여준다.
    // 오픈런 페이지는 메인과 같은 순서로 더 많이 보여준다.
    @Transactional(readOnly = true)
    public List<OpenRunDisplayDto> getOpenRuns() {
        return openRuns(OPEN_RUN_PAGE_SIZE);
    }

    private List<OpenRunDisplayDto> openRuns(int limit) {
        LocalDateTime now = LocalDateTime.now();
        return campaignActivityRepository.findAllByActivityTypeAndStatusIn(
                        CampaignActivityType.FIRST_COME_FIRST_SERVE,
                        List.of(CampaignActivityStatus.ACTIVE, CampaignActivityStatus.ENDED)).stream()
                .map(activity -> toOpenRun(activity, now))
                .sorted(Comparator.comparing(OpenRunDisplayDto::getState)
                        .thenComparing(StoreViewService::compareWithinState))
                .limit(limit)
                .toList();
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
                product.getBrand() != null ? product.getBrand() : "OPENRUN",
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

    // 상세 하단의 같은 카테고리 인기 상품. 보고 있는 상품과 사진이 같은 상품은 뺀다.
    @Transactional(readOnly = true)
    public List<ProductDisplayDto> getRelatedProducts(ProductDisplayDto product) {
        return topSellingProducts(product.getCategory()).stream()
                .filter(p -> !p.getId().equals(product.getId())
                        && !java.util.Objects.equals(p.getImageUrl(), product.getImageUrl()))
                .limit(DEAL_SIZE)
                .toList();
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

        List<CampaignActivityDisplayDto> raffleActivities = allActivities.stream()
                .filter(activity -> activity.getActivityType() == CampaignActivityType.GIVEAWAY)
                .collect(Collectors.toList());

        List<CampaignActivityDisplayDto> couponActivities = allActivities.stream()
                .filter(activity -> activity.getActivityType() == CampaignActivityType.COUPON)
                .collect(Collectors.toList());

        List<Long> issuedCouponIds = userId != null
                ? userCouponRepository.findAllCouponIdsByUserId(userId)
                : List.of();

        return CampaignActivitiesViewData.builder()
                .raffleCampaignActivities(raffleActivities)
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
                .brand(product.getBrand() != null ? product.getBrand() : "OPENRUN")
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
        private Map<String, List<ProductDisplayDto>> rankings;
        private int rankingDays;
        private Map<String, List<ProductDisplayDto>> categoryDeals;
        private List<OpenRunDisplayDto> openRuns;
        private String selectedCategory;
        private String selectedCategoryLabel;
        private List<ProductDisplayDto> categoryProducts;
        private int page;
        private boolean hasNext;
    }

    public enum OpenRunState {
        OPEN, UPCOMING, ENDED
    }

    @Getter
    @Builder
    public static class OpenRunDisplayDto {
        private Long id;
        private String title;
        private String imageUrl;
        private String price;
        private int discountRate;
        private int limitCount;
        private LocalDateTime startDate;
        private LocalDateTime endDate;
        private OpenRunState state;
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
