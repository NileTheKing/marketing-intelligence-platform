package com.axon.core_service.service;

import com.axon.core_service.domain.marketing.MarketingActionDispatch;
import com.axon.core_service.domain.marketing.MarketingActionDispatchInitiatedBy;
import com.axon.core_service.domain.marketing.MarketingActionExecution;
import com.axon.core_service.domain.marketing.MarketingActionExecutionStatus;
import com.axon.core_service.exception.BusinessConflictException;
import com.axon.core_service.exception.ResourceNotFoundException;
import com.axon.core_service.repository.MarketingActionDispatchRepository;
import com.axon.core_service.repository.MarketingActionExecutionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MarketingActionExecutionRetryClaimService {

    private final MarketingActionExecutionRepository executionRepository;
    private final MarketingActionDispatchRepository dispatchRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public MarketingActionDispatch claim(Long executionId) {
        MarketingActionExecution execution = executionRepository.findByIdForUpdate(executionId)
                .orElseThrow(() -> new ResourceNotFoundException("Marketing action execution", executionId));
        MarketingActionDispatch latest = dispatchRepository.findTopByExecution_IdOrderBySequenceDesc(executionId)
                .orElseThrow(() -> new BusinessConflictException("Execution has no dispatch: " + executionId));
        if (latest.getStatus() != MarketingActionExecutionStatus.FAILED_FINAL) {
            throw new BusinessConflictException(
                    "Execution is not a final failure or is already being retried: " + latest.getStatus());
        }

        MarketingActionDispatch retry = dispatchRepository.saveAndFlush(MarketingActionDispatch.builder()
                .execution(execution)
                .sequence(latest.getSequence() + 1)
                .initiatedBy(MarketingActionDispatchInitiatedBy.ADMIN)
                .build());
        if (dispatchRepository.markDispatching(retry.getId(),
                MarketingActionExecutionStatus.PENDING,
                MarketingActionExecutionStatus.DISPATCHING) != 1) {
            throw new BusinessConflictException("Dispatch could not be claimed for retry: " + retry.getId());
        }

        return dispatchRepository.findByIdWithExecution(retry.getId()).orElseThrow();
    }
}
