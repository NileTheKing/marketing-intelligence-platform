package com.axon.core_service.repository;

import com.axon.core_service.domain.user.UserSummary;
import com.axon.core_service.domain.user.RfmSegment;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserSummaryRepository extends JpaRepository<UserSummary, Long> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE UserSummary summary SET summary.lastPurchaseAt = :occurredAt " +
            "WHERE summary.userId = :userId " +
            "AND (summary.lastPurchaseAt IS NULL OR summary.lastPurchaseAt < :occurredAt)")
    int advanceLastPurchaseAt(@Param("userId") Long userId,
                              @Param("occurredAt") LocalDateTime occurredAt);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT summary FROM UserSummary summary WHERE summary.userId = :userId")
    Optional<UserSummary> findByIdForUpdate(@Param("userId") Long userId);

    @Query("SELECT summary.userId FROM UserSummary summary " +
            "WHERE summary.userId IN :userIds AND summary.rfmSegment = :rfmSegment")
    List<Long> findUserIdsByUserIdInAndRfmSegment(
            @Param("userIds") List<Long> userIds,
            @Param("rfmSegment") RfmSegment rfmSegment);
}
