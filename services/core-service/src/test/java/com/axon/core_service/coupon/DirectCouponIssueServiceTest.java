package com.axon.core_service.coupon;

import com.axon.core_service.campaign.CampaignActivityRepository;
import com.axon.core_service.campaign.domain.CampaignActivity;
import com.axon.core_service.campaign.dto.CampaignActivityStatus;
import com.axon.core_service.coupon.domain.Coupon;
import com.axon.core_service.coupon.domain.UserCoupon;
import com.axon.core_service.exception.BusinessConflictException;
import com.axon.core_service.exception.InvalidRequestException;
import com.axon.messaging.CampaignActivityType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DirectCouponIssueServiceTest {
    private final CampaignActivityRepository activities = mock(CampaignActivityRepository.class);
    private final CouponRepository coupons = mock(CouponRepository.class);
    private final UserCouponRepository issued = mock(UserCouponRepository.class);
    private final DirectCouponIssueService service = new DirectCouponIssueService(activities, coupons, issued);

    @Test
    void issuesCouponForEligibleDirectActivity() {
        // Given: an active direct coupon activity and a user who has not received it.
        Coupon coupon = mock(Coupon.class);
        when(coupon.getId()).thenReturn(10L);
        when(activities.findWithProductAndCouponById(1L)).thenReturn(Optional.of(activity(coupon, List.of())));
        when(coupons.findByIdForUpdate(10L)).thenReturn(Optional.of(coupon));
        UserCoupon result = mock(UserCoupon.class);
        when(result.getId()).thenReturn(20L);
        when(issued.saveAndFlush(any())).thenReturn(result);

        // When: the user requests the coupon.
        Long userCouponId = service.issue(1L, 42L);

        // Then: the saved user coupon is returned.
        assertThat(userCouponId).isEqualTo(20L);
        verify(issued, times(1)).saveAndFlush(any());
    }

    @Test
    void rejectsCouponAlreadyIssuedToUser() {
        // Given: this user already received the coupon.
        Coupon coupon = mock(Coupon.class);
        when(coupon.getId()).thenReturn(10L);
        when(activities.findWithProductAndCouponById(1L)).thenReturn(Optional.of(activity(coupon, List.of())));
        when(coupons.findByIdForUpdate(10L)).thenReturn(Optional.of(coupon));
        when(issued.existsByUserIdAndCouponId(42L, 10L)).thenReturn(true);

        // When / Then: another request is rejected without saving a second coupon.
        assertThatThrownBy(() -> service.issue(1L, 42L)).isInstanceOf(BusinessConflictException.class);
        verify(issued, never()).saveAndFlush(any());
    }

    @Test
    void rejectsConfiguredAudienceFiltersInsteadOfBypassingThem() {
        // Given: the existing activity has an audience condition.
        Coupon coupon = mock(Coupon.class);
        var filter = new com.axon.core_service.campaign.dto.filter.FilterDetail("AGE", "GTE", List.of("20"), "FAST");
        when(activities.findWithProductAndCouponById(1L)).thenReturn(Optional.of(activity(coupon, List.of(filter))));
        // When / Then: direct issuance is rejected without creating a coupon.
        assertThatThrownBy(() -> service.issue(1L, 42L)).isInstanceOf(InvalidRequestException.class);
        verifyNoInteractions(issued);
    }

    private CampaignActivity activity(Coupon coupon, List<com.axon.core_service.campaign.dto.filter.FilterDetail> filters) {
        return CampaignActivity.builder().coupon(coupon).activityType(CampaignActivityType.COUPON)
                .status(CampaignActivityStatus.ACTIVE).startDate(LocalDateTime.now().minusDays(1))
                .endDate(LocalDateTime.now().plusDays(1)).filters(filters).build();
    }
}
