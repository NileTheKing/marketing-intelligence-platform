package com.axon.core_service.service;

import com.axon.core_service.domain.marketing.MarketingActionDispatch;
import com.axon.core_service.domain.marketing.MarketingActionExecution;
import com.axon.core_service.domain.marketing.MarketingActionTriageCase;
import com.axon.core_service.domain.marketing.RewardType;
import com.axon.core_service.repository.MarketingActionDispatchRepository;
import com.axon.core_service.repository.MarketingActionExecutionRepository;
import com.axon.core_service.repository.MarketingActionRepository;
import com.axon.core_service.repository.MarketingActionTriageCaseRepository;
import com.axon.messaging.MarketingActionFailureCategory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MarketingActionTriageServiceTest {

    @Mock private MarketingActionTriageCaseRepository triageCaseRepository;
    @Mock private MarketingActionDispatchRepository dispatchRepository;
    @Mock private MarketingActionExecutionRepository executionRepository;
    @Mock private MarketingActionRepository actionRepository;
    @Mock private MarketingActionExecutionRetryClaimService retryClaimService;
    @Mock private KafkaTemplate<String, Object> kafkaTemplate;

    @Test
    void duplicateDltDoesNotCreateAnotherCaseForTheSameDispatch() {
        MarketingActionTriageService service = new MarketingActionTriageService(
                triageCaseRepository, dispatchRepository, executionRepository, actionRepository,
                retryClaimService, kafkaTemplate);
        MarketingActionDispatch dispatch = mock(MarketingActionDispatch.class);
        MarketingActionExecution execution = MarketingActionExecution.builder()
                .actionId(5L).ruleId(10L).actionReferenceId(99L).userId(1L).productId(100L)
                .channel(RewardType.WEBHOOK).build();
        when(dispatch.getExecution()).thenReturn(execution);
        when(dispatchRepository.findByIdWithExecution(11L)).thenReturn(Optional.of(dispatch));
        when(triageCaseRepository.findByDispatch_Id(11L))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(mock(MarketingActionTriageCase.class)));

        service.createForFinalFailure(11L, MarketingActionFailureCategory.RATE_LIMITED, "429");
        service.createForFinalFailure(11L, MarketingActionFailureCategory.RATE_LIMITED, "429");

        ArgumentCaptor<MarketingActionTriageCase> captor = ArgumentCaptor.forClass(MarketingActionTriageCase.class);
        verify(triageCaseRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getFailureCategory()).isEqualTo(MarketingActionFailureCategory.RATE_LIMITED);
    }
}
