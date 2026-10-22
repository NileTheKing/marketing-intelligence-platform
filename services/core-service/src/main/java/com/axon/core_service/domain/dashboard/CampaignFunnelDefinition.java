package com.axon.core_service.domain.dashboard;

import com.axon.messaging.CampaignActivityType;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Central mapping between campaign activity types and dashboard funnel steps.
 *
 * Only event types that are currently emitted by the runtime pipeline should be
 * registered here. Unsupported activity types intentionally return an empty
 * mapping so dashboards do not look populated by unrelated FCFS events.
 */
public final class CampaignFunnelDefinition {

    private static final Map<CampaignActivityType, Map<FunnelStep, List<String>>> DEFINITIONS = createDefinitions();

    private CampaignFunnelDefinition() {
    }

    public static List<String> triggerTypesFor(CampaignActivityType activityType, FunnelStep step) {
        if (activityType == null || step == null) {
            return List.of();
        }
        return DEFINITIONS.getOrDefault(activityType, Map.of())
                .getOrDefault(step, List.of());
    }

    public static long countForStep(Map<String, Long> triggerCounts, CampaignActivityType activityType, FunnelStep step) {
        if (triggerCounts == null || triggerCounts.isEmpty()) {
            return 0L;
        }
        return triggerTypesFor(activityType, step).stream()
                .mapToLong(triggerType -> triggerCounts.getOrDefault(triggerType, 0L))
                .sum();
    }

    private static Map<CampaignActivityType, Map<FunnelStep, List<String>>> createDefinitions() {
        Map<CampaignActivityType, Map<FunnelStep, List<String>>> definitions = new EnumMap<>(CampaignActivityType.class);

        EnumMap<FunnelStep, List<String>> fcfs = new EnumMap<>(FunnelStep.class);
        fcfs.put(FunnelStep.VISIT, List.of("PAGE_VIEW"));
        fcfs.put(FunnelStep.ENGAGE, List.of("CLICK"));
        fcfs.put(FunnelStep.QUALIFY, List.of("APPROVED"));
        fcfs.put(FunnelStep.PURCHASE, List.of("PURCHASE"));
        definitions.put(CampaignActivityType.FIRST_COME_FIRST_SERVE, Map.copyOf(fcfs));

        // COUPON / GIVEAWAY / WEBHOOK are intentionally not registered yet.
        // Add mappings only after their behavior events are actually emitted.
        return Map.copyOf(definitions);
    }
}
