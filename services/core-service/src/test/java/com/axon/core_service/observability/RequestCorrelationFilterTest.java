package com.axon.core_service.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestCorrelationFilterTest {

    @Test
    void preservesProxyRequestIdAndClearsMdcAfterTheRequest() throws Exception {
        RequestCorrelationFilter filter = new RequestCorrelationFilter();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Request-Id", "nginx-request-id");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) ->
                assertThat(MDC.get("request_id")).isEqualTo("nginx-request-id"));

        assertThat(response.getHeader("X-Request-Id")).isEqualTo("nginx-request-id");
        assertThat(MDC.get("request_id")).isNull();
    }
}

