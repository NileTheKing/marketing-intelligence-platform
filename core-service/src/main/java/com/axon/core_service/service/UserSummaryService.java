package com.axon.core_service.service;

import com.axon.core_service.domain.user.User;
import com.axon.core_service.repository.UserRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;

import com.axon.core_service.repository.PurchaseRepository;
import com.axon.core_service.repository.UserSummaryRepository;
import com.axon.core_service.domain.purchase.PurchaseStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserSummaryService {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Seoul");

    private final UserRepository userRepository;
    private final UserSummaryRepository userSummaryRepository;
    private final PurchaseRepository purchaseRepository;
    /**
     * Updates the user's purchase activity by recording a purchase that occurred at the given instant.
     *
     * @param userId     the identifier of the user whose purchase summary will be updated
     * @param occurredAt the timestamp when the purchase occurred
     * @throws IllegalArgumentException if no user exists with the given id
     */
    @Transactional
    public void recordPurchase(Long userId, Instant occurredAt) {
        advanceLastPurchaseAt(userId, occurredAt);
    }

    /**
     * Bulk 구매 기록 (마지막 구매 시간만 업데이트)
     *
     * @param latestPurchaseTimes userId -> 가장 최근 구매 시각
     */
    @Transactional
    public void recordLatestPurchaseBatch(Map<Long, Instant> latestPurchaseTimes) {
        if (latestPurchaseTimes.isEmpty()) {
            return;
        }

        log.info("Recording purchase for {} users", latestPurchaseTimes.size());

        for (Map.Entry<Long, Instant> entry : latestPurchaseTimes.entrySet()) {
            if (entry.getValue() != null) {
                advanceLastPurchaseAt(entry.getKey(), entry.getValue());
            }
        }

        log.info("Updated purchase time for {} users", latestPurchaseTimes.size());
    }

    @Transactional
    public void rebuildPurchaseSummary(Long userId) {
        var summary = userSummaryRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new IllegalArgumentException("User summary not found: " + userId));

        purchaseRepository.findFirstByUserIdAndStatusOrderByPurchaseAtDesc(userId, PurchaseStatus.CONFIRMED)
                .ifPresentOrElse(
                        purchase -> summary.updateLastPurchaseAt(
                                purchase.getPurchaseAt().atZone(BUSINESS_ZONE).toInstant()),
                        () -> summary.updateLastPurchaseAt(null));
    }
    /**
        * Records a login event for the specified user at the given timestamp, updating the user's activity summary.
     *
     * @param userId  the identifier of the user whose login should be recorded
     * @param loggedAt the instant when the login occurred
     * @throws IllegalArgumentException if no user exists with the given `userId`
     */
    @Transactional
    public void recordLogin(Long userId, Instant loggedAt) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
        user.recordLogin(loggedAt);
    }

    private void advanceLastPurchaseAt(Long userId, Instant occurredAt) {
        if (occurredAt == null) {
            return;
        }

        LocalDateTime candidate = LocalDateTime.ofInstant(occurredAt, BUSINESS_ZONE);
        int updated = userSummaryRepository.advanceLastPurchaseAt(userId, candidate);
        if (updated == 0 && !userSummaryRepository.existsById(userId)) {
            throw new IllegalArgumentException("User summary not found: " + userId);
        }
    }
}
