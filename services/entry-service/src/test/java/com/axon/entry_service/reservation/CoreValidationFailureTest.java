package com.axon.entry_service.reservation;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;

class CoreValidationFailureTest {
    @Test
    void coreFailureIsDistinctFromIneligibilityAndMapsTo503() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://core");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://core/api/v1/validation?campaignActivityId=1"))
                .andRespond(withServerError());
        CoreValidationService service = new CoreValidationService(builder.build());

        assertThatThrownBy(() -> service.isEligible("Bearer token", 1L))
                .isInstanceOf(CoreValidationUnavailableException.class);
        var response = new EntryController(null).validationUnavailable(new CoreValidationUnavailableException(null));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        server.verify();
    }
}
