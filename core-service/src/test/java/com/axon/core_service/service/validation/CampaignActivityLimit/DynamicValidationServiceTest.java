package com.axon.core_service.service.validation.CampaignActivityLimit;

import com.axon.core_service.domain.campaignactivity.CampaignActivity;
import com.axon.core_service.domain.dto.campaignactivity.filter.FilterDetail;
import com.axon.core_service.repository.CampaignActivityRepository;
import com.axon.messaging.dto.validation.ValidationResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DynamicValidationServiceTest {

    @Test
    void validateChecksEveryHeavyFilter() {
        CampaignActivityRepository repository = mock(CampaignActivityRepository.class);
        ValidationLimitStrategy first = strategy("FIRST");
        ValidationLimitStrategy second = strategy("SECOND");
        DynamicValidationService service = new DynamicValidationService(
                repository, new ValidationLimitFactoryService(List.of(first, second)));
        CampaignActivity activity = CampaignActivity.builder()
                .filters(List.of(
                        new FilterDetail("FIRST", null, List.of("1"), "HEAVY"),
                        new FilterDetail("SECOND", "GTE", List.of("2"), "HEAVY")))
                .build();
        when(repository.findById(10L)).thenReturn(Optional.of(activity));
        when(first.validateCampaignActivityLimit(1L, "BETWEEN", List.of("1")))
                .thenReturn(ValidationResponse.builder().eligible(true).build());
        when(second.validateCampaignActivityLimit(1L, "GTE", List.of("2")))
                .thenReturn(ValidationResponse.builder().eligible(true).build());

        ValidationResponse response = service.validate(1L, 10L);

        assertThat(response.isEligible()).isTrue();
        verify(first).validateCampaignActivityLimit(1L, "BETWEEN", List.of("1"));
        verify(second).validateCampaignActivityLimit(1L, "GTE", List.of("2"));
    }

    @Test
    void validateStopsAtFirstFailedHeavyFilter() {
        CampaignActivityRepository repository = mock(CampaignActivityRepository.class);
        ValidationLimitStrategy first = strategy("FIRST");
        ValidationLimitStrategy second = strategy("SECOND");
        DynamicValidationService service = new DynamicValidationService(
                repository, new ValidationLimitFactoryService(List.of(first, second)));
        CampaignActivity activity = CampaignActivity.builder()
                .filters(List.of(
                        new FilterDetail("FIRST", "GTE", List.of("1"), "HEAVY"),
                        new FilterDetail("SECOND", "GTE", List.of("2"), "HEAVY")))
                .build();
        ValidationResponse denied = ValidationResponse.builder()
                .eligible(false)
                .errorMessage("denied")
                .build();
        when(repository.findById(10L)).thenReturn(Optional.of(activity));
        when(first.validateCampaignActivityLimit(1L, "GTE", List.of("1"))).thenReturn(denied);

        ValidationResponse response = service.validate(1L, 10L);

        assertThat(response).isSameAs(denied);
        verify(second, never()).validateCampaignActivityLimit(1L, "GTE", List.of("2"));
    }

    @Test
    void validateAllowsActivityWhenThereIsNoHeavyFilter() {
        CampaignActivityRepository repository = mock(CampaignActivityRepository.class);
        DynamicValidationService service = new DynamicValidationService(
                repository, new ValidationLimitFactoryService(List.of()));
        CampaignActivity activity = CampaignActivity.builder()
                .filters(List.of(new FilterDetail("FAST", null, List.of("1"), "FAST")))
                .build();
        when(repository.findById(10L)).thenReturn(Optional.of(activity));

        assertThat(service.validate(1L, 10L).isEligible()).isTrue();
    }

    private static ValidationLimitStrategy strategy(String name) {
        ValidationLimitStrategy strategy = mock(ValidationLimitStrategy.class);
        when(strategy.getLimitName()).thenReturn(name);
        return strategy;
    }
}
