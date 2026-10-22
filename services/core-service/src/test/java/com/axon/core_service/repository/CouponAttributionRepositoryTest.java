package com.axon.core_service.repository;

import com.axon.core_service.campaign.domain.Campaign;
import com.axon.core_service.campaign.domain.CampaignActivity;
import com.axon.core_service.campaign.domain.CampaignActivityRun;
import com.axon.core_service.campaign.domain.CampaignActivityRunStatus;
import com.axon.core_service.campaign.dto.CampaignActivityStatus;
import com.axon.core_service.coupon.domain.Coupon;
import com.axon.core_service.coupon.domain.UserCoupon;
import com.axon.core_service.domain.dto.dashboard.PurchaseAggregate;
import com.axon.core_service.domain.dto.dashboard.PurchaseAggregateByCampaign;
import com.axon.core_service.domain.marketing.MarketingActionExecution;
import com.axon.core_service.domain.marketing.RewardType;
import com.axon.core_service.domain.purchase.Purchase;
import com.axon.core_service.domain.purchase.PurchaseType;
import com.axon.messaging.CampaignActivityType;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class CouponAttributionRepositoryTest {

    private static final Instant PURCHASED_AT = Instant.parse("2026-11-28T03:00:00Z");
    private static final LocalDateTime FROM = LocalDateTime.of(2026, 11, 1, 0, 0);
    private static final LocalDateTime TO = LocalDateTime.of(2026, 12, 1, 0, 0);

    @Autowired
    private CouponAttributionRepository repository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void attributesCouponPurchasesThroughExecutionRunToCampaign() {
        Campaign campaign = persist(Campaign.builder().name("coupon campaign").build());
        CampaignActivity activity = persist(couponActivity(campaign));
        CampaignActivityRun run = persist(CampaignActivityRun.builder()
                .campaignActivity(activity)
                .status(CampaignActivityRunStatus.DISPATCHED)
                .targetCount(10)
                .factSnapshot(Map.of())
                .build());
        Coupon coupon = persist(coupon("10% off"));

        UserCoupon campaignCoupon = userCoupon(1L, coupon, persist(execution(1L, run.getId(), null)).getId());
        UserCoupon ruleCoupon = userCoupon(2L, coupon, persist(execution(2L, null, 77L)).getId());
        UserCoupon manualCoupon = userCoupon(3L, coupon, null);

        persist(shopPurchase(1L, "30000", campaignCoupon.getId()));
        UserCoupon refundedCoupon = userCoupon(6L, coupon, persist(execution(6L, run.getId(), null)).getId());
        Purchase cancelled = persist(shopPurchase(6L, "99000", refundedCoupon.getId()));
        cancelled.cancel("changed mind", PURCHASED_AT.plusSeconds(60));
        persist(shopPurchase(2L, "20000", ruleCoupon.getId()));
        persist(shopPurchase(3L, "10000", manualCoupon.getId()));
        persist(shopPurchase(4L, "50000", null));
        entityManager.flush();
        entityManager.clear();

        List<PurchaseAggregateByCampaign> byCampaign = repository.findCouponPurchasesByCampaign(FROM, TO);
        PurchaseAggregate unattributed = repository.findUnattributedCouponPurchases(FROM, TO);

        assertThat(byCampaign).singleElement().satisfies(aggregate -> {
            assertThat(aggregate.campaignId()).isEqualTo(campaign.getId());
            assertThat(aggregate.purchaseCount()).isEqualTo(1L);
            assertThat(aggregate.gmv()).isEqualByComparingTo("30000");
        });
        assertThat(unattributed.purchaseCount()).isEqualTo(2L);
        assertThat(unattributed.gmv()).isEqualByComparingTo("30000");
    }

    @Test
    void purchasesOutsideTheWindowAreExcluded() {
        Coupon coupon = persist(coupon("5000 off"));
        UserCoupon manualCoupon = userCoupon(5L, coupon, null);
        persist(shopPurchase(5L, "10000", manualCoupon.getId()));
        entityManager.flush();

        PurchaseAggregate unattributed = repository.findUnattributedCouponPurchases(
                LocalDateTime.of(2026, 12, 1, 0, 0), LocalDateTime.of(2027, 1, 1, 0, 0));

        assertThat(unattributed.purchaseCount()).isZero();
        assertThat(unattributed.gmv()).isEqualByComparingTo("0");
    }

    private <T> T persist(T entity) {
        entityManager.persist(entity);
        return entity;
    }

    private Coupon coupon(String name) {
        return Coupon.builder().name(name)
                .startDate(FROM).endDate(TO)
                .build();
    }

    private UserCoupon userCoupon(Long userId, Coupon coupon, Long executionId) {
        return persist(UserCoupon.builder().userId(userId).coupon(coupon).marketingActionExecutionId(executionId).build());
    }

    private MarketingActionExecution execution(Long userId, Long runId, Long ruleId) {
        return MarketingActionExecution.builder()
                .actionId(1L).actionReferenceId(1L).userId(userId)
                .ruleId(ruleId).campaignActivityRunId(runId)
                .channel(RewardType.COUPON).build();
    }

    private Purchase shopPurchase(Long userId, String price, Long userCouponId) {
        return Purchase.builder()
                .userId(userId)
                .productId(100L)
                .purchaseType(PurchaseType.SHOP)
                .price(new BigDecimal(price))
                .quantity(1)
                .purchasedAt(PURCHASED_AT)
                .userCouponId(userCouponId)
                .build();
    }

    private CampaignActivity couponActivity(Campaign campaign) {
        LocalDateTime start = LocalDateTime.of(2026, 11, 1, 0, 0);
        return CampaignActivity.builder()
                .campaign(campaign)
                .name("cart coupon")
                .limitCount(100)
                .status(CampaignActivityStatus.ACTIVE)
                .startDate(start)
                .endDate(start.plusDays(30))
                .activityType(CampaignActivityType.COUPON)
                .price(BigDecimal.ZERO)
                .quantity(1)
                .build();
    }
}
