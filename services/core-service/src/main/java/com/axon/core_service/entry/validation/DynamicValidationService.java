package com.axon.core_service.entry.validation;

import com.axon.core_service.campaign.domain.CampaignActivity;
import com.axon.core_service.campaign.dto.filter.FilterDetail;
import com.axon.core_service.campaign.CampaignActivityRepository;
import com.axon.messaging.dto.validation.ValidationResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class DynamicValidationService {
    private final CampaignActivityRepository campaignActivityRepository;
    private final ValidationLimitFactoryService validationLimitFactoryService;

    /**
     * Validates whether a user satisfies the participation limits of a campaign activity.
     *
     * <p>When the campaign has no filters, returns an eligible response. For each filter with phase
     * "HEAVY", delegates validation to the corresponding ValidationLimitStrategy and returns its result.
     * If a strategy is missing or validation fails, propagates an error.</p>
     *
     * @param userId             the identifier of the user to validate
     * @param campaignActivityId the identifier of the campaign activity whose limits are validated
     * @return a ValidationResponse indicating eligibility and, on failure, a user-facing error message
     * @throws IllegalArgumentException if the campaignActivityId does not correspond to an existing campaign activity
     */
    @Transactional(readOnly = true)
    public ValidationResponse validate(Long userId, Long campaignActivityId) {
        CampaignActivity campaignActivity = campaignActivityRepository.findById(campaignActivityId)
                .orElseThrow(() -> new IllegalArgumentException("campaignActivityId not found" +  campaignActivityId));
        List<FilterDetail> limitFilter = campaignActivity.getFilters();

        log.info("리미트 필터 확인 limitFilter : {}", limitFilter);
        //filter가 없으면 바로 통과시키기
        if(limitFilter == null || limitFilter.isEmpty()) {return ValidationResponse.builder().eligible(true).build();}
        try {
            for (FilterDetail filter : limitFilter) {
                if (!"HEAVY".equals(filter.getPhase())) {
                    continue;
                }
                String filterName = filter.getType();
                String operator = filter.getOperator() != null ? filter.getOperator() : "BETWEEN";
                List<String> filterValues = filter.getValues();

                //필터 이름과 매핑되는 함수를 호출 함
                ValidationLimitStrategy strategy = validationLimitFactoryService.getStrategy(filterName);
                if(strategy == null) {
                    log.warn("{} 의 전략함수는 존재하지 않습니다.", filterName);
                    throw new IllegalStateException("Unknown validation strategy: " + filterName);
                }
                ValidationResponse response = strategy.validateCampaignActivityLimit(userId, operator, filterValues);
                if (!response.isEligible()) {
                    return response;
                }
            }
        } catch (Exception err) {
            log.error("Dynamic Validation Error (userId: {})", userId, err);
            throw new IllegalStateException("Failed to validate participation conditions", err);
        }
        return ValidationResponse.builder().eligible(true).build();
    }
}
