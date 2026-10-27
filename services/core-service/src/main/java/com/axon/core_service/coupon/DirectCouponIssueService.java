package com.axon.core_service.coupon;

import com.axon.core_service.campaign.CampaignActivityRepository;
import com.axon.core_service.campaign.domain.CouponDeliveryMode;
import com.axon.core_service.campaign.dto.CampaignActivityStatus;
import com.axon.core_service.coupon.domain.UserCoupon;
import com.axon.core_service.exception.BusinessConflictException;
import com.axon.core_service.exception.InvalidRequestException;
import com.axon.core_service.exception.ResourceNotFoundException;
import com.axon.messaging.CampaignActivityType;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DirectCouponIssueService {
    private final CampaignActivityRepository activities;
    private final CouponRepository coupons;
    private final UserCouponRepository userCoupons;

    // Recheck after acquiring the coupon lock against the latest committed rows.
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Long issue(long activityId, long userId) {
        var activity = activities.findWithProductAndCouponById(activityId)
                .orElseThrow(() -> new ResourceNotFoundException("campaign activity", activityId));
        var now = LocalDateTime.now();
        if (activity.getActivityType() != CampaignActivityType.COUPON
                || activity.getCouponDeliveryMode() != CouponDeliveryMode.DIRECT
                || activity.getStatus() != CampaignActivityStatus.ACTIVE
                || now.isBefore(activity.getStartDate()) || now.isAfter(activity.getEndDate())
                || activity.getCoupon() == null) {
            throw new InvalidRequestException("발급 가능한 쿠폰 활동이 아닙니다.");
        }
        // Existing audience filters must not silently become unrestricted.
        if (activity.getFilters() != null && !activity.getFilters().isEmpty()) {
            throw new InvalidRequestException("참여 조건이 설정된 쿠폰은 직접 발급할 수 없습니다.");
        }
        var coupon = coupons.findByIdForUpdate(activity.getCoupon().getId())
                .orElseThrow(() -> new ResourceNotFoundException("coupon", activity.getCoupon().getId()));
        if (userCoupons.existsByUserIdAndCouponId(userId, coupon.getId())) {
            throw new BusinessConflictException("이미 발급받은 쿠폰입니다.");
        }
        return userCoupons.saveAndFlush(UserCoupon.builder().userId(userId).coupon(coupon).build()).getId();
    }
}
