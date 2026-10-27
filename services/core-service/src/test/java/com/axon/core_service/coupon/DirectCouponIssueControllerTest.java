package com.axon.core_service.coupon;

import com.axon.core_service.exception.BusinessConflictException;
import com.axon.core_service.support.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DirectCouponIssueControllerTest {
    @Test
    void returnsCreatedAfterServiceAndConflictForDuplicate() throws Exception {
        var service = mock(DirectCouponIssueService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new DirectCouponIssueController(service))
                .setControllerAdvice(new ApiExceptionHandler()).build();
        var principal = new UsernamePasswordAuthenticationToken("42", "n/a");
        when(service.issue(1L, 42L)).thenReturn(20L).thenThrow(new BusinessConflictException("이미 발급받은 쿠폰입니다."));
        mvc.perform(post("/api/v1/coupon-activities/1/issuances").principal(principal))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.userCouponId").value(20));
        mvc.perform(post("/api/v1/coupon-activities/1/issuances").principal(principal))
                .andExpect(status().isConflict());
    }
}
