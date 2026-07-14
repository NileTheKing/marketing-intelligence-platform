package com.axon.core_service.repository;

import com.axon.core_service.domain.marketing.MarketingAction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MarketingActionRepository extends JpaRepository<MarketingAction, Long> {
    List<MarketingAction> findByMarketingRuleIdInAndIsActiveTrue(List<Long> marketingRuleIds);
}
