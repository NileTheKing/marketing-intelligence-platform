package com.axon.core_service.domain.dashboard;

import com.axon.messaging.CampaignActivityType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CampaignFunnelDefinitionTest {

    @Test
    @DisplayName("FCFS 퍼널 단계는 기존 triggerType 매핑을 유지한다")
    void triggerTypesForFcfs() {
        assertThat(CampaignFunnelDefinition.triggerTypesFor(
                CampaignActivityType.FIRST_COME_FIRST_SERVE, FunnelStep.VISIT))
                .containsExactly("PAGE_VIEW");
        assertThat(CampaignFunnelDefinition.triggerTypesFor(
                CampaignActivityType.FIRST_COME_FIRST_SERVE, FunnelStep.ENGAGE))
                .containsExactly("CLICK");
        assertThat(CampaignFunnelDefinition.triggerTypesFor(
                CampaignActivityType.FIRST_COME_FIRST_SERVE, FunnelStep.QUALIFY))
                .containsExactly("APPROVED");
        assertThat(CampaignFunnelDefinition.triggerTypesFor(
                CampaignActivityType.FIRST_COME_FIRST_SERVE, FunnelStep.PURCHASE))
                .containsExactly("PURCHASE");
    }

    @Test
    @DisplayName("미구현 캠페인 타입은 FCFS 매핑으로 fallback하지 않는다")
    void triggerTypesForUnsupportedTypesAreEmpty() {
        for (CampaignActivityType activityType : CampaignActivityType.values()) {
            if (activityType == CampaignActivityType.FIRST_COME_FIRST_SERVE) {
                continue;
            }

            for (FunnelStep step : FunnelStep.values()) {
                assertThat(CampaignFunnelDefinition.triggerTypesFor(activityType, step))
                        .as("%s %s", activityType, step)
                        .isEmpty();
            }
        }
    }

    @Test
    @DisplayName("triggerType 집계 결과를 공통 FunnelStep 기준으로 합산한다")
    void countForStepUsesMappedTriggerTypesOnly() {
        Map<String, Long> triggerCounts = Map.of(
                "PAGE_VIEW", 100L,
                "CLICK", 40L,
                "APPROVED", 12L,
                "PURCHASE", 10L);

        assertThat(CampaignFunnelDefinition.countForStep(
                triggerCounts, CampaignActivityType.FIRST_COME_FIRST_SERVE, FunnelStep.VISIT))
                .isEqualTo(100L);
        assertThat(CampaignFunnelDefinition.countForStep(
                triggerCounts, CampaignActivityType.FIRST_COME_FIRST_SERVE, FunnelStep.ENGAGE))
                .isEqualTo(40L);
        assertThat(CampaignFunnelDefinition.countForStep(
                triggerCounts, CampaignActivityType.FIRST_COME_FIRST_SERVE, FunnelStep.QUALIFY))
                .isEqualTo(12L);
        assertThat(CampaignFunnelDefinition.countForStep(
                triggerCounts, CampaignActivityType.FIRST_COME_FIRST_SERVE, FunnelStep.PURCHASE))
                .isEqualTo(10L);
    }

    @Test
    @DisplayName("미구현 타입은 FCFS 이벤트 카운트가 있어도 퍼널 count를 0으로 계산한다")
    void unsupportedTypeCountIsZeroEvenWithFcfsEvents() {
        Map<String, Long> triggerCounts = Map.of(
                "PAGE_VIEW", 100L,
                "CLICK", 40L,
                "APPROVED", 12L,
                "PURCHASE", 10L);

        for (FunnelStep step : FunnelStep.values()) {
            assertThat(CampaignFunnelDefinition.countForStep(
                    triggerCounts, CampaignActivityType.COUPON, step))
                    .as("COUPON %s", step)
                    .isZero();
        }
    }
}
