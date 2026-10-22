package com.axon.core_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.axon.core_service.domain.campaign.Campaign;
import com.axon.core_service.domain.campaignactivity.CampaignActivity;
import com.axon.core_service.domain.campaignactivity.CouponDeliveryMode;
import com.axon.core_service.coupon.domain.Coupon;
import com.axon.core_service.domain.dto.campaignactivity.CampaignActivityRequest;
import com.axon.core_service.domain.dto.campaignactivity.CampaignActivityStatus;
import com.axon.core_service.domain.marketing.AbandonedCartCouponCampaign;
import com.axon.core_service.domain.marketing.MarketingAction;
import com.axon.core_service.domain.marketing.RewardType;
import com.axon.core_service.domain.marketing.DormantCustomerCampaign;
import com.axon.core_service.exception.BusinessConflictException;
import com.axon.core_service.repository.AbandonedCartCouponCampaignRepository;
import com.axon.core_service.repository.CampaignActivityEntryRepository;
import com.axon.core_service.repository.CampaignActivityRepository;
import com.axon.core_service.repository.CampaignRepository;
import com.axon.core_service.coupon.CouponRepository;
import com.axon.core_service.repository.DormantCustomerCampaignRepository;
import com.axon.core_service.repository.MarketingActionRepository;
import com.axon.core_service.repository.ProductRepository;
import com.axon.messaging.CampaignActivityType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

class CampaignActivityCouponDeliveryModeTest {

    private final CampaignRepository campaigns = mock(CampaignRepository.class);
    private final CampaignActivityRepository activities = mock(CampaignActivityRepository.class);
    private final CouponRepository coupons = mock(CouponRepository.class);
    private final MarketingActionRepository actions = mock(MarketingActionRepository.class);
    private final AbandonedCartCouponCampaignRepository abandoned = mock(AbandonedCartCouponCampaignRepository.class);
    private final DormantCustomerCampaignRepository dormant = mock(DormantCustomerCampaignRepository.class);
    private final CampaignActivityService service = new CampaignActivityService(
            campaigns, activities, mock(CampaignActivityEntryRepository.class), mock(ProductRepository.class),
            coupons, actions, abandoned, dormant, mock(StringRedisTemplate.class));

    @Test
    void createsAbandonedCartConfigWithActivityAndAllowsCouponDefaultsWithoutPriceOrQuantity() {
        Campaign campaign = Campaign.builder().name("spring").build();
        Coupon coupon = Coupon.builder().name("coupon").build();
        ReflectionTestUtils.setField(coupon, "id", 12L);
        MarketingAction action = MarketingAction.builder().actionType(RewardType.COUPON)
                .referenceId(12L).isActive(true).build();
        when(campaigns.findById(1L)).thenReturn(Optional.of(campaign));
        when(coupons.findById(12L)).thenReturn(Optional.of(coupon));
        when(actions.save(any(MarketingAction.class))).thenReturn(action);
        when(activities.save(any(CampaignActivity.class))).thenAnswer(call -> call.getArgument(0));
        when(abandoned.save(any(AbandonedCartCouponCampaign.class))).thenAnswer(call -> call.getArgument(0));

        var response = service.createCampaignActivity(1L, request(CouponDeliveryMode.ABANDONED_CART, 30, null));

        assertThat(response.getCouponDeliveryMode()).isEqualTo(CouponDeliveryMode.ABANDONED_CART);
        assertThat(response.getDelayMinutes()).isEqualTo(30);
        assertThat(response.getPrice()).isZero();
        assertThat(response.getQuantity()).isZero();
        verify(abandoned).save(any(AbandonedCartCouponCampaign.class));
    }

    @Test
    void rejectsCrossModeAndNonCouponAutomationSettings() {
        assertThatThrownBy(() -> service.createCampaignActivity(1L,
                request(CouponDeliveryMode.ABANDONED_CART, null, 10)))
                .isInstanceOf(BusinessConflictException.class);

        CampaignActivityRequest nonCoupon = CampaignActivityRequest.builder()
                .name("FCFS")
                .status(CampaignActivityStatus.DRAFT)
                .startDate(LocalDateTime.now())
                .endDate(LocalDateTime.now().plusDays(1))
                .activityType(CampaignActivityType.FIRST_COME_FIRST_SERVE)
                .price(BigDecimal.ONE)
                .quantity(1)
                .couponDeliveryMode(CouponDeliveryMode.DORMANT_CUSTOMER)
                .dormantDays(30)
                .build();
        assertThatThrownBy(() -> service.createCampaignActivity(1L, nonCoupon))
                .isInstanceOf(BusinessConflictException.class);
    }

    @Test
    void createsDormantCustomerConfigAndReturnsItsThreshold() {
        when(campaigns.findById(1L)).thenReturn(Optional.of(Campaign.builder().name("spring").build()));
        when(coupons.findById(12L)).thenReturn(Optional.of(coupon()));
        when(actions.save(any(MarketingAction.class))).thenAnswer(call -> call.getArgument(0));
        when(activities.save(any(CampaignActivity.class))).thenAnswer(call -> call.getArgument(0));
        when(dormant.save(any(DormantCustomerCampaign.class))).thenAnswer(call -> call.getArgument(0));

        var response = service.createCampaignActivity(1L,
                request(CouponDeliveryMode.DORMANT_CUSTOMER, null, 60));

        assertThat(response.getCouponDeliveryMode()).isEqualTo(CouponDeliveryMode.DORMANT_CUSTOMER);
        assertThat(response.getDormantDays()).isEqualTo(60);
        verify(dormant).save(any(DormantCustomerCampaign.class));
    }

    @Test
    void rejectsDeliveryModeChangesOnUpdate() {
        CampaignActivity activity = CampaignActivity.builder().couponDeliveryMode(CouponDeliveryMode.DIRECT).build();
        when(activities.findById(9L)).thenReturn(Optional.of(activity));

        assertThatThrownBy(() -> service.updateCampaignActivity(9L,
                request(CouponDeliveryMode.DORMANT_CUSTOMER, null, 30)))
                .isInstanceOf(BusinessConflictException.class)
                .hasMessageContaining("cannot be changed");
    }

    @Test
    void updatesAutomationThresholdOnlyWhileActivityIsPaused() {
        LocalDateTime now = LocalDateTime.now();
        CampaignActivity activity = CampaignActivity.builder()
                .campaign(Campaign.builder().name("spring").build())
                .name("cart coupon")
                .limitCount(500)
                .status(CampaignActivityStatus.PAUSED)
                .startDate(now.minusDays(1))
                .endDate(now.plusDays(1))
                .activityType(CampaignActivityType.COUPON)
                .couponDeliveryMode(CouponDeliveryMode.ABANDONED_CART)
                .coupon(coupon())
                .marketingAction(MarketingAction.builder().actionType(RewardType.COUPON)
                        .referenceId(12L).isActive(true).build())
                .price(BigDecimal.ZERO)
                .quantity(0)
                .build();
        AbandonedCartCouponCampaign config = AbandonedCartCouponCampaign.builder()
                .campaignActivity(activity)
                .marketingAction(activity.getMarketingAction())
                .delayMinutes(30)
                .isActive(false)
                .build();
        activity.assignAbandonedCartCouponCampaign(config);
        when(activities.findById(10L)).thenReturn(Optional.of(activity));
        when(coupons.findById(12L)).thenReturn(Optional.of(activity.getCoupon()));

        var response = service.updateCampaignActivity(10L,
                request(CouponDeliveryMode.ABANDONED_CART, 45, null, CampaignActivityStatus.PAUSED));

        assertThat(response.getDelayMinutes()).isEqualTo(45);
        assertThat(config.getDelayMinutes()).isEqualTo(45);
    }

    private CampaignActivityRequest request(CouponDeliveryMode mode, Integer delay, Integer dormantDays) {
        return request(mode, delay, dormantDays, CampaignActivityStatus.ACTIVE);
    }

    private CampaignActivityRequest request(CouponDeliveryMode mode, Integer delay, Integer dormantDays,
                                            CampaignActivityStatus status) {
        return CampaignActivityRequest.builder()
                .name("automated coupon")
                .limitCount(500)
                .status(status)
                .startDate(LocalDateTime.now().minusDays(1))
                .endDate(LocalDateTime.now().plusDays(1))
                .activityType(CampaignActivityType.COUPON)
                .couponId(12L)
                .couponDeliveryMode(mode)
                .delayMinutes(delay)
                .dormantDays(dormantDays)
                .purpose(com.axon.core_service.domain.campaignactivity.CampaignActivityPurpose.REENGAGEMENT)
                .operatorMemo("Re-engage customers")
                .build();
    }

    private Coupon coupon() {
        Coupon coupon = Coupon.builder().name("coupon").build();
        ReflectionTestUtils.setField(coupon, "id", 12L);
        return coupon;
    }
}
