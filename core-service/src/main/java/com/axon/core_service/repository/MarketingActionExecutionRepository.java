package com.axon.core_service.repository;

import com.axon.core_service.domain.marketing.MarketingActionExecution;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface MarketingActionExecutionRepository extends JpaRepository<MarketingActionExecution, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from MarketingActionExecution e where e.id = :id")
    Optional<MarketingActionExecution> findByIdForUpdate(@Param("id") Long id);
}
