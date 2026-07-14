package com.axon.core_service.service.strategy;

import com.axon.core_service.domain.coupon.Coupon;
import com.axon.core_service.domain.coupon.UserCoupon;
import com.axon.core_service.repository.CouponRepository;
import com.axon.core_service.repository.UserCouponRepository;
import com.axon.messaging.CampaignActivityType;
import com.axon.messaging.dto.CampaignActivityKafkaProducerDto;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CouponStrategyTest {

    @Mock
    private UserCouponRepository userCouponRepository;

    @Mock
    private CouponRepository couponRepository;

    @Test
    @DisplayName("쿠폰 배치는 기존 발급 내역을 한 번에 조회하고 배치 내부 중복도 제거한다")
    @SuppressWarnings("unchecked")
    void processBatch_PrefetchesExistingCouponsAndSkipsDuplicates() {
        CouponStrategy strategy = new CouponStrategy(userCouponRepository, couponRepository);
        Coupon coupon = coupon(10L);
        UserCoupon existing = UserCoupon.builder()
                .userId(2L)
                .coupon(coupon)
                .build();

        when(couponRepository.findAllById(List.of(10L))).thenReturn(List.of(coupon));
        when(userCouponRepository.findAllByUserIdInAndCouponIdIn(anyCollection(), anyCollection()))
                .thenReturn(List.of(existing));

        strategy.processBatch(List.of(
                message(1L, 10L),
                message(1L, 10L),
                message(2L, 10L)
        ));

        verify(userCouponRepository).findAllByUserIdInAndCouponIdIn(List.of(1L, 2L), List.of(10L));
        ArgumentCaptor<Iterable<UserCoupon>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(userCouponRepository).saveAll(captor.capture());

        List<UserCoupon> saved = new ArrayList<>();
        captor.getValue().forEach(saved::add);

        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getUserId()).isEqualTo(1L);
        assertThat(saved.get(0).getCoupon().getId()).isEqualTo(10L);
    }

    @Test
    @DisplayName("actionReferenceId가 있으면 couponId보다 우선한다 (마케팅 룰 액션 메시지)")
    void processBatch_PrefersActionReferenceIdOverCouponId() {
        CouponStrategy strategy = new CouponStrategy(userCouponRepository, couponRepository);
        Coupon coupon = coupon(20L);

        when(couponRepository.findAllById(List.of(20L))).thenReturn(List.of(coupon));
        when(userCouponRepository.findAllByUserIdInAndCouponIdIn(anyCollection(), anyCollection()))
                .thenReturn(List.of());

        CampaignActivityKafkaProducerDto message = CampaignActivityKafkaProducerDto.builder()
                .campaignActivityType(CampaignActivityType.COUPON)
                .userId(1L)
                .actionReferenceId(20L)
                .couponId(999L) // should be ignored in favor of actionReferenceId
                .timestamp(1234L)
                .build();

        strategy.processBatch(List.of(message));

        verify(userCouponRepository).findAllByUserIdInAndCouponIdIn(List.of(1L), List.of(20L));
    }

    private CampaignActivityKafkaProducerDto message(Long userId, Long couponId) {
        return CampaignActivityKafkaProducerDto.builder()
                .campaignActivityType(CampaignActivityType.COUPON)
                .campaignActivityId(100L)
                .userId(userId)
                .couponId(couponId)
                .timestamp(1234L)
                .build();
    }

    private Coupon coupon(Long id) {
        Coupon coupon = Coupon.builder()
                .name("test coupon")
                .discountAmount(BigDecimal.valueOf(1000))
                .startDate(LocalDateTime.now().minusDays(1))
                .endDate(LocalDateTime.now().plusDays(1))
                .build();
        ReflectionTestUtils.setField(coupon, "id", id);
        return coupon;
    }
}
