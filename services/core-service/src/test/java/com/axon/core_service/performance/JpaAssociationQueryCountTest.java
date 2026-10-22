package com.axon.core_service.performance;

import com.axon.core_service.domain.campaign.Campaign;
import com.axon.core_service.domain.campaignactivity.CampaignActivity;
import com.axon.core_service.domain.coupon.Coupon;
import com.axon.core_service.domain.coupon.UserCoupon;
import com.axon.core_service.domain.dashboard.LTVBatch;
import com.axon.core_service.domain.dto.campaignactivity.CampaignActivityStatus;
import com.axon.core_service.domain.product.Product;
import com.axon.core_service.repository.CampaignActivityRepository;
import com.axon.core_service.repository.CampaignRepository;
import com.axon.core_service.repository.CouponRepository;
import com.axon.core_service.repository.LTVBatchRepository;
import com.axon.core_service.repository.ProductRepository;
import com.axon.core_service.repository.UserCouponRepository;
import com.axon.messaging.CampaignActivityType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "spring.jpa.show-sql=false",
        "logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=OFF"
})
@DisplayName("JPA association query count regression checks")
class JpaAssociationQueryCountTest {

    private static final int ACTIVITY_COUNT = 20;
    private static final int USER_COUPON_COUNT = 10;
    private static final int LTV_MONTH_COUNT = 12;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private CampaignRepository campaignRepository;

    @Autowired
    private CampaignActivityRepository campaignActivityRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private CouponRepository couponRepository;

    @Autowired
    private UserCouponRepository userCouponRepository;

    @Autowired
    private LTVBatchRepository ltvBatchRepository;

    private Campaign campaign;
    private CampaignActivity ltvActivity;

    @BeforeEach
    void setUp() {
        LocalDateTime now = LocalDateTime.now();
        campaign = campaignRepository.save(Campaign.builder()
                .name("query-count-campaign")
                .budget(BigDecimal.valueOf(1_000_000))
                .build());

        for (int i = 0; i < ACTIVITY_COUNT / 2; i++) {
            Product product = productRepository.save(new Product(
                    "product-" + i,
                    100L,
                    BigDecimal.valueOf(10_000 + i),
                    "TECH"));
            campaignActivityRepository.save(CampaignActivity.builder()
                    .campaign(campaign)
                    .product(product)
                    .name("product-activity-" + i)
                    .limitCount(100)
                    .status(CampaignActivityStatus.ACTIVE)
                    .startDate(now.minusDays(1))
                    .endDate(now.plusDays(1))
                    .activityType(CampaignActivityType.FIRST_COME_FIRST_SERVE)
                    .price(product.getPrice())
                    .quantity(1)
                    .budget(BigDecimal.valueOf(100_000))
                    .build());
        }

        for (int i = 0; i < ACTIVITY_COUNT / 2; i++) {
            Coupon coupon = createCoupon("coupon-" + i);
            campaignActivityRepository.save(CampaignActivity.builder()
                    .campaign(campaign)
                    .coupon(coupon)
                    .name("coupon-activity-" + i)
                    .limitCount(100)
                    .status(CampaignActivityStatus.ACTIVE)
                    .startDate(now.minusDays(1))
                    .endDate(now.plusDays(1))
                    .activityType(CampaignActivityType.COUPON)
                    .price(BigDecimal.ZERO)
                    .quantity(0)
                    .budget(BigDecimal.valueOf(100_000))
                    .build());
        }

        for (int i = 0; i < USER_COUPON_COUNT; i++) {
            userCouponRepository.save(UserCoupon.builder()
                    .userId(777L)
                    .coupon(createCoupon("user-coupon-" + i))
                    .build());
        }

        ltvActivity = campaignActivityRepository.findAllByStatus(CampaignActivityStatus.ACTIVE).getFirst();
        for (int i = 0; i < LTV_MONTH_COUNT; i++) {
            ltvBatchRepository.save(LTVBatch.builder()
                    .campaignActivity(ltvActivity)
                    .monthOffset(i)
                    .collectedAt(now)
                    .cohortStartDate(now.minusMonths(1))
                    .cohortSize(100)
                    .avgCac(BigDecimal.valueOf(1000))
                    .ltvCumulative(BigDecimal.valueOf(10_000L + i))
                    .ltvCacRatio(BigDecimal.valueOf(10))
                    .cumulativeProfit(BigDecimal.valueOf(100_000L + i))
                    .isBreakEven(true)
                    .monthlyRevenue(BigDecimal.valueOf(10_000L + i))
                    .monthlyOrders(10)
                    .activeUsers(10)
                    .repeatPurchaseRate(BigDecimal.valueOf(30))
                    .avgPurchaseFrequency(BigDecimal.valueOf(1.5))
                    .avgOrderValue(BigDecimal.valueOf(10_000))
                    .build());
        }

        entityManager.flush();
        entityManager.clear();
        statistics().clear();
    }

    @Test
    @DisplayName("ACTIVE activity list: product/coupon EntityGraph reduces association query count")
    void activeActivitiesEntityGraphQueryCount() {
        long baseline = countPreparedStatements(() -> {
            List<CampaignActivity> activities = entityManager.createQuery(
                            "SELECT ca FROM CampaignActivity ca WHERE ca.status = :status",
                            CampaignActivity.class)
                    .setParameter("status", CampaignActivityStatus.ACTIVE)
                    .getResultList();

            activities.forEach(activity -> {
                if (activity.getProduct() != null) {
                    activity.getProduct().getPrice();
                }
                if (activity.getCoupon() != null) {
                    activity.getCoupon().getCouponName();
                }
            });
        });

        long optimized = countPreparedStatements(() -> {
            List<CampaignActivity> activities = campaignActivityRepository.findAllByStatus(CampaignActivityStatus.ACTIVE);
            activities.forEach(activity -> {
                if (activity.getProduct() != null) {
                    activity.getProduct().getPrice();
                }
                if (activity.getCoupon() != null) {
                    activity.getCoupon().getCouponName();
                }
            });
        });

        System.out.printf("[QUERY_COUNT] active activities baseline=%d optimized=%d%n", baseline, optimized);
        assertThat(optimized).isLessThan(baseline);
    }

    @Test
    @DisplayName("UserCoupon list: coupon EntityGraph reduces association query count")
    void userCouponsEntityGraphQueryCount() {
        long baseline = countPreparedStatements(() -> {
            List<UserCoupon> userCoupons = entityManager.createQuery(
                            "SELECT uc FROM UserCoupon uc WHERE uc.userId = :userId",
                            UserCoupon.class)
                    .setParameter("userId", 777L)
                    .getResultList();

            userCoupons.forEach(userCoupon -> userCoupon.getCoupon().getCouponName());
        });

        long optimized = countPreparedStatements(() -> {
            List<UserCoupon> userCoupons = userCouponRepository.findAllByUserId(777L);
            userCoupons.forEach(userCoupon -> userCoupon.getCoupon().getCouponName());
        });

        System.out.printf("[QUERY_COUNT] user coupons baseline=%d optimized=%d%n", baseline, optimized);
        assertThat(optimized).isLessThan(baseline);
    }

    @Test
    @DisplayName("LTV batch list: campaignActivity EntityGraph keeps dashboard batch lookup bounded")
    void ltvBatchEntityGraphQueryCount() {
        long baseline = countPreparedStatements(() -> {
            List<LTVBatch> stats = entityManager.createQuery(
                            "SELECT lb FROM LTVBatch lb WHERE lb.campaignActivity.id = :activityId ORDER BY lb.monthOffset ASC",
                            LTVBatch.class)
                    .setParameter("activityId", ltvActivity.getId())
                    .getResultList();

            stats.forEach(stat -> stat.getCampaignActivity().getName());
        });

        long optimized = countPreparedStatements(() -> {
            List<LTVBatch> stats = ltvBatchRepository.findByCampaignActivityIdOrderByMonthOffsetAsc(ltvActivity.getId());
            stats.forEach(stat -> stat.getCampaignActivity().getName());
        });

        System.out.printf("[QUERY_COUNT] ltv batch baseline=%d optimized=%d%n", baseline, optimized);
        assertThat(optimized).isLessThan(baseline);
    }

    private Coupon createCoupon(String name) {
        LocalDateTime now = LocalDateTime.now();
        return couponRepository.save(Coupon.builder()
                .name(name)
                .discountAmount(BigDecimal.valueOf(1000))
                .discountRate(10)
                .minOrderAmount(BigDecimal.ZERO)
                .targetCategory("TECH")
                .startDate(now.minusDays(1))
                .endDate(now.plusDays(30))
                .build());
    }

    private long countPreparedStatements(Runnable action) {
        entityManager.flush();
        entityManager.clear();
        Statistics statistics = statistics();
        statistics.clear();

        action.run();

        entityManager.flush();
        return statistics.getPrepareStatementCount();
    }

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }
}
