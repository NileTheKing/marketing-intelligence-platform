package com.axon.core_service.coupon;

import com.axon.core_service.coupon.domain.Coupon;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CouponRepository extends JpaRepository<Coupon, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select c from Coupon c where c.id = :id")
    java.util.Optional<Coupon> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") Long id);
}
