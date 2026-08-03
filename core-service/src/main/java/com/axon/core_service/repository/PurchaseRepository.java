package com.axon.core_service.repository;

import com.axon.core_service.domain.purchase.Purchase;
import com.axon.core_service.domain.purchase.PurchaseType;
import com.axon.core_service.domain.purchase.PurchaseStatus;
import com.axon.core_service.domain.dto.user.UserRfmMetricsDto;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface PurchaseRepository extends JpaRepository<Purchase, Long> {

        // 사용자별 구매 내역 조회
        List<Purchase> findByUserId(Long userId);

        // 상품별 구매 내역 조회
        List<Purchase> findByProductId(Long productId);

        // 구매 타입별 조회
        List<Purchase> findByPurchaseType(PurchaseType purchaseType);

        // 캠페인별 구매 내역 조회
        List<Purchase> findByCampaignActivityId(Long campaignActivityId);

        @Query("SELECT p FROM Purchase p WHERE p.campaignActivityId IN :activityIds AND p.userId IN :userIds")
        List<Purchase> findByCampaignActivityIdsAndUserIds(
                        @Param("activityIds") List<Long> activityIds,
                        @Param("userIds") List<Long> userIds);

        // 캠페인별 영속 Purchase 건수 (Redis FCFS 대사용; 취소/환불 이력 포함)
        long countByCampaignActivityId(Long campaignActivityId);

        long countByCampaignActivityIdAndStatus(Long campaignActivityId, PurchaseStatus status);

        java.util.Optional<Purchase> findFirstByUserIdAndStatusOrderByPurchaseAtDesc(Long userId, PurchaseStatus status);

        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // Cohort Analysis Queries
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

        /**
         * 특정 Activity의 특정 기간 내 모든 구매 조회
         */
        @Query("SELECT p FROM Purchase p " +
                        "WHERE p.campaignActivityId = :activityId " +
                        "AND p.status = 'CONFIRMED' " +
                        "AND p.purchaseAt >= :startDate " +
                        "AND p.purchaseAt < :endDate " +
                        "ORDER BY p.purchaseAt ASC")
        List<Purchase> findByCampaignActivityIdAndPeriod(
                        @Param("activityId") Long activityId,
                        @Param("startDate") LocalDateTime startDate,
                        @Param("endDate") LocalDateTime endDate);

        /**
         * 특정 Activity의 첫 구매 고객만 조회 (Cohort 정의)
         * 해당 기간 내 구매 중, 유저의 생애 첫 구매인 경우만 조회
         */
        @Query("SELECT p FROM Purchase p " +
                        "WHERE p.campaignActivityId = :activityId " +
                        "AND p.status = 'CONFIRMED' " +
                        "AND p.purchaseAt >= :startDate " +
                        "AND p.purchaseAt < :endDate " +
                        "AND NOT EXISTS (" +
                        "    SELECT 1 FROM Purchase prev " +
                        "    WHERE prev.userId = p.userId " +
                        "    AND prev.status = 'CONFIRMED' " +
                        "    AND prev.purchaseAt < p.purchaseAt" +
                        ")")
        List<Purchase> findFirstPurchasesByActivityAndPeriod(
                        @Param("activityId") Long activityId,
                        @Param("startDate") LocalDateTime startDate,
                        @Param("endDate") LocalDateTime endDate);

        /**
         * 특정 userId 목록의 모든 구매 이력 조회 (재구매 추적용)
         */
        @Query("SELECT p FROM Purchase p " +
                        "WHERE p.userId IN :userIds " +
                        "AND p.status = 'CONFIRMED' " +
                        "ORDER BY p.userId, p.purchaseAt ASC")
        List<Purchase> findByUserIdIn(@Param("userIds") List<Long> userIds);

        /**
         * 특정 유저 목록의 특정 기간 내 구매 이력 조회 (LTV 증분 계산용)
         */
        @Query("SELECT p FROM Purchase p " +
                        "WHERE p.userId IN :userIds " +
                        "AND p.status = 'CONFIRMED' " +
                        "AND p.purchaseAt >= :startDate " +
                        "AND p.purchaseAt < :endDate " +
                        "ORDER BY p.purchaseAt ASC")
        List<Purchase> findByUserIdInAndPeriod(
                        @Param("userIds") List<Long> userIds,
                        @Param("startDate") LocalDateTime startDate,
                        @Param("endDate") LocalDateTime endDate);

        @Query("SELECT new com.axon.core_service.domain.dto.user.UserRfmMetricsDto(" +
                        "p.userId, COUNT(p), COALESCE(SUM(p.price * p.quantity), 0)) " +
                        "FROM Purchase p WHERE p.userId IN :userIds AND p.status = 'CONFIRMED' GROUP BY p.userId")
        List<UserRfmMetricsDto> findRfmMetricsByUserIdIn(@Param("userIds") List<Long> userIds);

        /**
         * 특정 Activity에서 재구매한 고객 수 조회
         */
        @Query("SELECT COUNT(DISTINCT p.userId) FROM Purchase p " +
                        "WHERE p.campaignActivityId = :activityId " +
                        "AND p.status = 'CONFIRMED' " +
                        "AND p.userId IN :cohortUserIds " +
                        "GROUP BY p.userId " +
                        "HAVING COUNT(p.id) > 1")
        Long countRepeatCustomers(
                        @Param("activityId") Long activityId,
                        @Param("cohortUserIds") List<Long> cohortUserIds);

        /**
         * 특정 유저가 구매(참여)한 모든 CampaignActivity ID 조회
         */
        @Query("SELECT p.campaignActivityId FROM Purchase p WHERE p.userId = :userId AND p.status = 'CONFIRMED' AND p.campaignActivityId IS NOT NULL")
        List<Long> findPurchasedCampaignActivityIdsByUserId(@Param("userId") Long userId);

        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // Reconciliation Queries (대사/정산)
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

        /**
         * [Ghost 데이터 색인]
         * 특정 기간 동안 발생한 Event 구매 건 중, CampaignActivityEntry(참여 내역) 레코드가 없는 고아 데이터 조회
         */
        @Query("SELECT p FROM Purchase p " +
               "WHERE p.purchaseType = 'CAMPAIGNACTIVITY' " +
               "AND p.status = 'CONFIRMED' " +
               "AND p.purchaseAt >= :startDate AND p.purchaseAt < :endDate " +
               "AND p.campaignActivityId IS NOT NULL " +
               "AND NOT EXISTS (" +
               "    SELECT 1 FROM CampaignActivityEntry c " +
               "    WHERE c.campaignActivity.id = p.campaignActivityId " +
               "    AND c.userId = p.userId" +
               ")")
        List<Purchase> findGhostPurchases(
                @Param("startDate") LocalDateTime startDate,
                @Param("endDate") LocalDateTime endDate);

        @Query("SELECT new com.axon.core_service.domain.dto.dashboard.PurchaseAggregate(" +
                "COUNT(p), COALESCE(SUM(p.price * p.quantity), 0)) " +
                "FROM Purchase p WHERE p.campaignActivityId = :activityId " +
                "AND p.status = 'CONFIRMED' " +
                "AND p.purchaseAt >= :startDate AND p.purchaseAt < :endDate")
        com.axon.core_service.domain.dto.dashboard.PurchaseAggregate findConfirmedAggregateByActivityIdAndPeriod(
                @Param("activityId") Long activityId,
                @Param("startDate") LocalDateTime startDate,
                @Param("endDate") LocalDateTime endDate);

        @Query("SELECT new com.axon.core_service.domain.dto.dashboard.PurchaseAggregateByActivity(" +
                "p.campaignActivityId, COUNT(p), COALESCE(SUM(p.price * p.quantity), 0)) " +
                "FROM Purchase p WHERE p.campaignActivityId IN :activityIds " +
                "AND p.status = 'CONFIRMED' " +
                "AND p.purchaseAt >= :startDate AND p.purchaseAt < :endDate " +
                "GROUP BY p.campaignActivityId")
        List<com.axon.core_service.domain.dto.dashboard.PurchaseAggregateByActivity> findConfirmedAggregatesByActivityIdsAndPeriod(
                @Param("activityIds") List<Long> activityIds,
                @Param("startDate") LocalDateTime startDate,
                @Param("endDate") LocalDateTime endDate);
}
