package com.axon.core_service.domain.dto.marketing;

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
        MarketingActionExecutionStatus status,
        int attemptCount,
        long dispatchVersion,
        String lastFailureReason,
        LocalDateTime dispatchedAt,
        LocalDateTime completedAt,
        LocalDateTime dltAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static MarketingActionExecutionResponse from(MarketingActionExecution execution) {
        return new MarketingActionExecutionResponse(
                execution.getId(), execution.getActionId(), execution.getRuleId(), execution.getActionReferenceId(),
                execution.getUserId(), execution.getProductId(), execution.getChannel(), execution.getStatus(),
                execution.getAttemptCount(), execution.getDispatchVersion(), execution.getLastFailureReason(), execution.getDispatchedAt(),
                execution.getCompletedAt(), execution.getDltAt(), execution.getCreatedAt(), execution.getUpdatedAt());
    }
}
