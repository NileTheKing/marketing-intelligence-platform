package com.axon.core_service.service;

import com.axon.core_service.domain.user.RfmSegment;
import com.axon.core_service.domain.user.UserSummary;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

@Service
public class RfmSegmentationService {

    /**
     * RFM 기준에 따라 세그먼트를 산정합니다.
     * VIP      : Recency ≤ 30일 AND Frequency ≥ 3 AND Monetary ≥ 100,000
     * LOYAL    : Recency ≤ 60일 AND Frequency ≥ 2
     * AT_RISK  : Recency > 60일 AND Frequency ≥ 1
     * DORMANT  : 그 외 (구매 없음 또는 장기 미구매)
     *
     * @param summary 유저 서머리 (Recency 판별에 사용)
     * @param frequency 구매 횟수 (UserMetric 등에서 전달)
     * @param monetary 누적 구매액 (UserMetric 등에서 전달)
     * @param now 기준 시간
     * @return 판별된 RfmSegment
     */
    public RfmSegment calculateSegment(UserSummary summary, int frequency, long monetary, LocalDateTime now) {
        if (summary == null || summary.getLastPurchaseAt() == null || frequency == 0) {
            return RfmSegment.DORMANT;
        }

        long daysSinceLastPurchase = ChronoUnit.DAYS.between(summary.getLastPurchaseAt(), now);

        if (daysSinceLastPurchase <= 30 && frequency >= 3 && monetary >= 100_000) {
            return RfmSegment.VIP;
        }

        if (daysSinceLastPurchase <= 60 && frequency >= 2) {
            return RfmSegment.LOYAL;
        }

        if (daysSinceLastPurchase > 60 && frequency >= 1) {
            return RfmSegment.AT_RISK;
        }

        return RfmSegment.DORMANT;
    }
}
