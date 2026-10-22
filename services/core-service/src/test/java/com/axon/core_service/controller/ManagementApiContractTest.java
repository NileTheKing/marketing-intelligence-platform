package com.axon.core_service.controller;

import com.axon.core_service.config.TestSecurityConfig;
import com.axon.core_service.config.auth.CustomLogoutHandler;
import com.axon.core_service.config.auth.CustomOAuth2UserService;
import com.axon.core_service.config.auth.OAuth2AuthenticationSuccessHandler;
import com.axon.core_service.config.auth.SecurityConfig;
import com.axon.core_service.domain.dto.event.EventResponse;
import com.axon.core_service.domain.event.EventStatus;
import com.axon.core_service.domain.event.TriggerType;
import com.axon.core_service.exception.InvalidRequestException;
import com.axon.core_service.exception.ResourceNotFoundException;
import com.axon.core_service.service.CouponService;
import com.axon.core_service.service.EventService;
import com.axon.core_service.support.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {EventController.class, CouponController.class})
@Import({SecurityConfig.class, TestSecurityConfig.class, ApiExceptionHandler.class})
class ManagementApiContractTest {

    private static final String VALID_EVENT = """
            {
              "name": "Purchase click",
              "description": "purchase button click",
              "triggerType": "CLICK",
              "triggerPayload": {"trackId": "purchase-button"},
              "status": "ACTIVE"
            }
            """;

    private static final String VALID_COUPON = """
            {
              "couponName": "welcome",
              "discountAmount": 1000,
              "startDate": "2026-08-01T00:00:00",
              "endDate": "2026-09-01T00:00:00"
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EventService eventService;

    @MockitoBean
    private CouponService couponService;

    @MockitoBean
    private CustomOAuth2UserService customOAuth2UserService;

    @MockitoBean
    private OAuth2AuthenticationSuccessHandler oAuth2AuthenticationSuccessHandler;

    @MockitoBean
    private CustomLogoutHandler customLogoutHandler;

    @MockitoBean
    private JpaMetamodelMappingContext jpaMetamodelMappingContext;

    @Test
    void activeEventDefinitionsRemainPublic() throws Exception {
        when(eventService.getActiveEventDefinitions(null)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/events/active"))
                .andExpect(status().isOk());
    }

    @Test
    void regularUserCannotMutateEventOrCouponDefinitions() throws Exception {
        mockMvc.perform(post("/api/v1/events")
                        .with(user("1").roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_EVENT))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/coupons")
                        .with(user("1").roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_COUPON))
                .andExpect(status().isForbidden());

        verifyNoInteractions(eventService, couponService);
    }

    @Test
    void adminCreatesEventWithCreatedContract() throws Exception {
        EventResponse response = EventResponse.builder()
                .id(11L)
                .name("Purchase click")
                .description("purchase button click")
                .status(EventStatus.ACTIVE)
                .triggerType(TriggerType.CLICK)
                .triggerPayload(Map.of("trackId", "purchase-button"))
                .build();
        when(eventService.createEvent(any())).thenReturn(response);

        mockMvc.perform(post("/api/v1/events")
                        .with(user("1").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_EVENT))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/events/11"))
                .andExpect(jsonPath("$.id").value(11));
    }

    @Test
    void malformedEventRequestIsRejectedBeforeService() throws Exception {
        mockMvc.perform(post("/api/v1/events")
                        .with(user("1").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"description":"missing name","triggerType":"CLICK","triggerPayload":{}}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));

        verifyNoInteractions(eventService);
    }

    @Test
    void invalidEventDefinitionReturnsBadRequest() throws Exception {
        when(eventService.createEvent(any()))
                .thenThrow(new InvalidRequestException("CLICK event requires selector or trackId"));

        mockMvc.perform(post("/api/v1/events")
                        .with(user("1").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_EVENT))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }

    @Test
    void missingEventReturnsNotFound() throws Exception {
        when(eventService.getEvent(999L)).thenThrow(new ResourceNotFoundException("event", 999L));

        mockMvc.perform(get("/api/v1/events/999").with(user("1").roles("ADMIN")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void adminCreatesAndDeletesCouponWithResourceStatuses() throws Exception {
        when(couponService.createCoupon(any())).thenReturn(21L);

        mockMvc.perform(post("/api/v1/coupons")
                        .with(user("1").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_COUPON))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/coupons/21"))
                .andExpect(jsonPath("$").value(21));

        mockMvc.perform(delete("/api/v1/coupons/21").with(user("1").roles("ADMIN")))
                .andExpect(status().isNoContent());
    }

    @Test
    void invalidAndMissingCouponUseDifferentFailureContracts() throws Exception {
        when(couponService.createCoupon(any()))
                .thenThrow(new InvalidRequestException("할인 금액은 0보다 커야 합니다."));
        when(couponService.updateCoupon(eq(999L), any()))
                .thenThrow(new ResourceNotFoundException("coupon", 999L));

        mockMvc.perform(post("/api/v1/coupons")
                        .with(user("1").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_COUPON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));

        mockMvc.perform(put("/api/v1/coupons/999")
                        .with(user("1").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_COUPON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("RESOURCE_NOT_FOUND"));
    }
}
