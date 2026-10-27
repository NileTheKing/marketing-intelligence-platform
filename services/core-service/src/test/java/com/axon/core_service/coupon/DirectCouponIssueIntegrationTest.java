package com.axon.core_service.coupon;

import com.axon.core_service.AbstractIntegrationTest;
import com.axon.core_service.campaign.CampaignActivityRepository;
import com.axon.core_service.campaign.CampaignRepository;
import com.axon.core_service.campaign.domain.Campaign;
import com.axon.core_service.campaign.domain.CampaignActivity;
import com.axon.core_service.campaign.dto.CampaignActivityStatus;
import com.axon.core_service.coupon.domain.Coupon;
import com.axon.core_service.exception.BusinessConflictException;
import com.axon.messaging.CampaignActivityType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;

class DirectCouponIssueIntegrationTest extends AbstractIntegrationTest {
    @Autowired DirectCouponIssueService service;
    @Autowired CampaignRepository campaigns;
    @Autowired CampaignActivityRepository activities;
    @Autowired CouponRepository coupons;
    @Autowired JdbcTemplate jdbc;

    @Test
    void concurrentRequestsCreateOneCouponAndRejectTheOther() throws Exception {
        // Given: an active direct coupon activity and two ready requests for the same user.
        var now = LocalDateTime.now();
        var campaign = campaigns.saveAndFlush(Campaign.builder().name("concurrent coupon test").build());
        var coupon = coupons.saveAndFlush(Coupon.builder().name("concurrent test").discountRate(10)
                .startDate(now.minusDays(1)).endDate(now.plusDays(1)).build());
        var activity = activities.saveAndFlush(CampaignActivity.builder().campaign(campaign).coupon(coupon)
                .name("direct coupon").activityType(CampaignActivityType.COUPON).status(CampaignActivityStatus.ACTIVE)
                .startDate(now.minusDays(1)).endDate(now.plusDays(1)).price(BigDecimal.ZERO).quantity(0).build());
        long userId = 99887766L;
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> issue(ready, start, activity.getId(), userId));
            var second = executor.submit(() -> issue(ready, start, activity.getId(), userId));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();

            // When: both requests start together.
            start.countDown();

            // Then: one succeeds and the other reports a duplicate.
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("ISSUED", "DUPLICATE");
        }
        // The database contains exactly one issued coupon.
        assertThat(jdbc.queryForObject("select count(*) from user_coupons where user_id = ? and coupon_id = ?",
                Long.class, userId, coupon.getId())).isEqualTo(1L);
    }

    private String issue(CountDownLatch ready, CountDownLatch start, long activityId, long userId) throws Exception {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start timed out");
        try {
            service.issue(activityId, userId);
            return "ISSUED";
        } catch (BusinessConflictException duplicate) {
            return "DUPLICATE";
        }
    }
}
