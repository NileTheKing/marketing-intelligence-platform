package com.axon.core_service.domain.dto.marketing;

import com.axon.core_service.domain.marketing.MarketingActionDispatch;
import com.axon.core_service.domain.marketing.MarketingActionDispatchInitiatedBy;
import com.axon.core_service.domain.marketing.MarketingActionExecution;
import com.axon.core_service.domain.marketing.MarketingActionExecutionStatus;
import com.axon.core_service.domain.marketing.RewardType;

import java.time.LocalDateTime;

public record MarketingActionExecutionResponse(
        Long id,
        Long actionId,
        Long ruleId,
        Long actionReferenceId,
        Long userId,
        Long productId,
        RewardType channel,
        Long dispatchId,
        long sequence,
        MarketingActionDispatchInitiatedBy initiatedBy,
        MarketingActionExecutionStatus status,
        int attemptCount,
        String lastFailureReason,
        LocalDateTime dispatchedAt,
        LocalDateTime completedAt,
        LocalDateTime dltAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static MarketingActionExecutionResponse from(MarketingActionDispatch dispatch) {
        MarketingActionExecution execution = dispatch.getExecution();
        return new MarketingActionExecutionResponse(
                execution.getId(), execution.getActionId(), execution.getRuleId(), execution.getActionReferenceId(),
                execution.getUserId(), execution.getProductId(), execution.getChannel(),
                dispatch.getId(), dispatch.getSequence(), dispatch.getInitiatedBy(), dispatch.getStatus(),
                dispatch.getAttemptCount(), dispatch.getLastFailureReason(), dispatch.getDispatchedAt(),
                dispatch.getCompletedAt(), dispatch.getDltAt(), execution.getCreatedAt(), execution.getUpdatedAt());
    }
}
