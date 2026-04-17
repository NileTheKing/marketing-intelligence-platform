package com.axon.core_service.repository;

import com.axon.core_service.domain.marketing.MarketingRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MarketingRuleRepository extends JpaRepository<MarketingRule, Long> {
    List<MarketingRule> findByIsActiveTrue();
}
