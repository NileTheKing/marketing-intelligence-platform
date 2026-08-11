package com.axon.core_service.repository;

import com.axon.core_service.AbstractIntegrationTest;
import com.axon.core_service.domain.marketing.AudienceSegment;
import com.axon.core_service.domain.marketing.MarketingAction;
import com.axon.core_service.domain.marketing.MarketingRule;
import com.axon.core_service.domain.marketing.RewardType;
import com.axon.core_service.domain.user.RfmSegment;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MarketingTriggerFetchGraphIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MarketingRuleRepository marketingRuleRepository;

    @Autowired
    private MarketingActionRepository marketingActionRepository;

    @Autowired
    private AudienceSegmentRepository audienceSegmentRepository;

    @BeforeEach
    void cleanUp() {
        marketingActionRepository.deleteAll();
        marketingRuleRepository.deleteAll();
        audienceSegmentRepository.deleteAll();
    }

    @Test
    void schedulerQueriesInitializeAssociationsUsedOutsideRepositoryTransaction() {
        AudienceSegment segment = audienceSegmentRepository.save(AudienceSegment.builder()
                .name("vip-segment")
                .targetRfmSegment(RfmSegment.VIP)
                .isActive(true)
                .build());
        MarketingRule rule = marketingRuleRepository.save(MarketingRule.builder()
                .ruleName("vip-view-rule")
                .behaviorType("PAGE_VIEW")
                .thresholdCount(3)
                .lookbackDays(7)
                .isActive(true)
                .audienceSegment(segment)
                .build());
        marketingActionRepository.save(MarketingAction.builder()
                .marketingRule(rule)
                .actionType(RewardType.COUPON)
                .referenceId(1L)
                .isActive(true)
                .build());

        MarketingRule loadedRule = marketingRuleRepository.findByIsActiveTrue().getFirst();
        MarketingAction loadedAction = marketingActionRepository
                .findByMarketingRuleIdInAndIsActiveTrue(List.of(rule.getId()))
                .getFirst();

        assertThat(Hibernate.isInitialized(loadedRule.getAudienceSegment())).isTrue();
        assertThat(Hibernate.isInitialized(loadedAction.getMarketingRule())).isTrue();
    }
}
