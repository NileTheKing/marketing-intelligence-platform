package com.axon.core_service.repository;

import com.axon.core_service.domain.coupon.UserCoupon;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserCouponRepository extends JpaRepository<UserCoupon, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT uc FROM UserCoupon uc WHERE uc.id = :id")
    Optional<UserCoupon> findByIdForUpdate(@Param("id") Long id);

    @Query("SELECT uc.coupon.id FROM UserCoupon uc WHERE uc.userId = :userId")
    List<Long> findAllCouponIdsByUserId(Long userId);

    @EntityGraph(attributePaths = "coupon")
    List<UserCoupon> findAllByUserId(Long userId);

    @EntityGraph(attributePaths = "coupon")
    @Query("""
            SELECT uc
            FROM UserCoupon uc
            WHERE uc.userId IN :userIds
              AND uc.coupon.id IN :couponIds
            """)
    List<UserCoupon> findAllByUserIdInAndCouponIdIn(
            @Param("userIds") Collection<Long> userIds,
            @Param("couponIds") Collection<Long> couponIds);
}
