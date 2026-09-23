package com.axon.core_service.domain.dto.marketing.triage;

import com.axon.core_service.domain.marketing.MarketingActionDispatch;
import com.axon.core_service.domain.marketing.MarketingActionTriageCase;

import java.util.List;

public record TriageDispatchContextResponse(
        Long dispatchId,
        Long executionId,
        Long actionId,
        Long ruleId,
        Long actionReferenceId,
        Long userId,
        Long productId,
        String channel,
        String actionType,
        Boolean actionActive,
        String ruleName,
        String behaviorType,
        Long targetProductId,
        Integer thresholdCount,
        Integer lookbackDays,
        TriageDispatchResponse dispatch,
        String failureCategory,
        String failureReason,
        String operatorGuidance,
        List<TriageDispatchResponse> previousDispatches
) {
    public static TriageDispatchContextResponse from(MarketingActionDispatch dispatch,
                                                      MarketingActionTriageCase triageCase,
                                                      List<MarketingActionDispatch> history,
                                                      String operatorGuidance,
                                                      com.axon.core_service.domain.marketing.MarketingAction action) {
        var execution = dispatch.getExecution();
        var rule = action == null ? null : action.getMarketingRule();
        return new TriageDispatchContextResponse(
                dispatch.getId(), execution.getId(), execution.getActionId(), execution.getRuleId(),
                execution.getActionReferenceId(), execution.getUserId(), execution.getProductId(),
                execution.getChannel().name(), action == null ? null : action.getActionType().name(),
                action == null ? null : action.isActive(), rule == null ? null : rule.getRuleName(),
                rule == null ? null : rule.getBehaviorType(), rule == null ? null : rule.getTargetProductId(),
                rule == null ? null : rule.getThresholdCount(), rule == null ? null : rule.getLookbackDays(),
                TriageDispatchResponse.from(dispatch),
                triageCase == null ? "UNKNOWN" : triageCase.getFailureCategory().name(),
                dispatch.getLastFailureReason(), operatorGuidance,
                history.stream().filter(item -> !item.getId().equals(dispatch.getId()))
                        .map(TriageDispatchResponse::from).toList());
    }
}
