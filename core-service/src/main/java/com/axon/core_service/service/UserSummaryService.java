package com.axon.core_service.service;

import com.axon.core_service.domain.user.User;
import com.axon.core_service.repository.UserRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.axon.core_service.repository.PurchaseRepository;
import com.axon.core_service.repository.UserSummaryRepository;
import com.axon.core_service.domain.purchase.PurchaseStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
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
    private final JdbcTemplate jdbcTemplate;

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

        advanceLastPurchaseAtBatch(latestPurchaseTimes);

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

    private void advanceLastPurchaseAtBatch(Map<Long, Instant> latestPurchaseTimes) {
        Map<Long, LocalDateTime> candidates = new LinkedHashMap<>();
        latestPurchaseTimes.forEach((userId, occurredAt) -> {
            if (occurredAt != null) {
                candidates.put(userId, LocalDateTime.ofInstant(occurredAt, BUSINESS_ZONE));
            }
        });
        if (candidates.isEmpty()) {
            return;
        }

        String placeholders = String.join(",", java.util.Collections.nCopies(candidates.size(), "?"));
        List<Long> existingIds = jdbcTemplate.query(
                "SELECT user_id FROM user_summary WHERE user_id IN (" + placeholders + ")",
                (rs, rowNum) -> rs.getLong(1),
                candidates.keySet().toArray());
        Set<Long> existing = new LinkedHashSet<>(existingIds);
        for (Long userId : candidates.keySet()) {
            if (!existing.contains(userId)) {
                throw new IllegalArgumentException("User summary not found: " + userId);
            }
        }

        StringBuilder sql = new StringBuilder("UPDATE user_summary SET last_purchase_at = CASE user_id ");
        List<Object> args = new ArrayList<>(candidates.size() * 3 + candidates.size());
        candidates.forEach((userId, occurredAt) -> {
            sql.append("WHEN ? THEN CASE WHEN last_purchase_at IS NULL OR last_purchase_at < ? THEN ? ELSE last_purchase_at END ");
            args.add(userId);
            args.add(occurredAt);
            args.add(occurredAt);
        });
        sql.append("ELSE last_purchase_at END WHERE user_id IN (").append(placeholders).append(")");
        args.addAll(candidates.keySet());
        jdbcTemplate.update(sql.toString(), args.toArray());
    }
}
