package com.axon.core_service.domain.marketing;

import com.axon.messaging.MarketingActionFailureCategory;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class MarketingActionTriageCaseTest {

    @Test
    void claimIsValidOnlyForTheCurrentUnexpiredToken() {
        MarketingActionDispatch dispatch = mock(MarketingActionDispatch.class);
        MarketingActionTriageCase triageCase = MarketingActionTriageCase.pending(
                dispatch, MarketingActionFailureCategory.TRANSIENT_DELIVERY, "timeout", "check endpoint");
        LocalDateTime now = LocalDateTime.now();

        triageCase.claim("claim-1", now.plusMinutes(10));

        assertThat(triageCase.hasValidClaim("claim-1", now)).isTrue();
        assertThat(triageCase.hasValidClaim("other", now)).isFalse();
        assertThat(triageCase.hasValidClaim("claim-1", now.plusMinutes(11))).isFalse();
        assertThat(triageCase.getAnalysisAttemptCount()).isEqualTo(1);
    }

    @Test
    void deterministicUnknownCategoryIsPreservedWhenDltDoesNotClassifyFailure() {
        MarketingActionTriageCase triageCase = MarketingActionTriageCase.pending(
                mock(MarketingActionDispatch.class), null, "unknown", "manual review");

        assertThat(triageCase.getFailureCategory()).isEqualTo(MarketingActionFailureCategory.UNKNOWN);
    }
}
