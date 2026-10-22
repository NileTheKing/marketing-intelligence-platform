package com.axon.core_service.controller;

import com.axon.core_service.config.TestSecurityConfig;
import com.axon.core_service.config.auth.CustomLogoutHandler;
import com.axon.core_service.config.auth.CustomOAuth2UserService;
import com.axon.core_service.config.auth.OAuth2AuthenticationSuccessHandler;
import com.axon.core_service.config.auth.SecurityConfig;
import com.axon.core_service.domain.dto.campaignactivity.CampaignActivityResponse;
import com.axon.core_service.domain.dto.campaignactivity.CampaignActivityStatus;
import com.axon.core_service.exception.BusinessConflictException;
import com.axon.core_service.exception.ResourceNotFoundException;
import com.axon.core_service.service.CampaignActivityService;
import com.axon.core_service.support.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = CampaignActivityController.class)
@Import({SecurityConfig.class, TestSecurityConfig.class, ApiExceptionHandler.class})
class CampaignActivityApiContractTest {

    private static final String VALID_REQUEST = """
            {
              "name": "선착순 이벤트",
              "limitCount": 100,
              "status": "DRAFT",
              "startDate": "2026-08-12T10:00:00",
              "endDate": "2026-08-13T10:00:00",
              "activityType": "FIRST_COME_FIRST_SERVE",
              "price": 10000,
              "quantity": 1
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CampaignActivityService campaignActivityService;

    @MockitoBean
    private CustomOAuth2UserService customOAuth2UserService;

    @MockitoBean
    private OAuth2AuthenticationSuccessHandler oAuth2AuthenticationSuccessHandler;

    @MockitoBean
    private CustomLogoutHandler customLogoutHandler;

    @MockitoBean
    private JpaMetamodelMappingContext jpaMetamodelMappingContext;

    @Test
    void anonymousAndRegularUserCannotCreateActivity() throws Exception {
        mockMvc.perform(post("/api/v1/campaigns/1/activities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_REQUEST))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/campaigns/1/activities")
                        .with(user("1").roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_REQUEST))
                .andExpect(status().isForbidden());

        verifyNoInteractions(campaignActivityService);
    }

    @Test
    void adminCreatesActivityWithCreatedContract() throws Exception {
        CampaignActivityResponse response = CampaignActivityResponse.builder()
                .id(10L)
                .campaignId(1L)
                .name("선착순 이벤트")
                .status(CampaignActivityStatus.DRAFT)
                .build();
        when(campaignActivityService.createCampaignActivity(eq(1L), any())).thenReturn(response);

        mockMvc.perform(post("/api/v1/campaigns/1/activities")
                        .with(user("1").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_REQUEST))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/campaign-activities/10"))
                .andExpect(jsonPath("$.id").value(10));
    }

    @Test
    void invalidDatesAndNegativeValuesReturnValidationError() throws Exception {
        String invalidRequest = VALID_REQUEST
                .replace("2026-08-13T10:00:00", "2026-08-11T10:00:00")
                .replace("\"price\": 10000", "\"price\": -1");

        mockMvc.perform(post("/api/v1/campaigns/1/activities")
                        .with(user("1").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidRequest))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));

        verifyNoInteractions(campaignActivityService);
    }

    @Test
    void zeroQuantityForProductActivityReturnsValidationError() throws Exception {
        String invalidRequest = VALID_REQUEST.replace("\"quantity\": 1", "\"quantity\": 0");

        mockMvc.perform(post("/api/v1/campaigns/1/activities")
                        .with(user("1").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidRequest))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));

        verifyNoInteractions(campaignActivityService);
    }

    @Test
    void zeroQuantityRemainsValidForCouponActivity() throws Exception {
        CampaignActivityResponse response = CampaignActivityResponse.builder()
                .id(11L)
                .campaignId(1L)
                .name("쿠폰 이벤트")
                .status(CampaignActivityStatus.DRAFT)
                .build();
        when(campaignActivityService.createCampaignActivity(eq(1L), any())).thenReturn(response);
        String couponRequest = VALID_REQUEST
                .replace("FIRST_COME_FIRST_SERVE", "COUPON")
                .replace("\"quantity\": 1", "\"quantity\": 0");

        mockMvc.perform(post("/api/v1/campaigns/1/activities")
                        .with(user("1").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(couponRequest))
                .andExpect(status().isCreated());
    }

    @Test
    void missingActivityReturnsNotFoundContract() throws Exception {
        when(campaignActivityService.getCampaignActivity(999L))
                .thenThrow(new ResourceNotFoundException("campaign activity", 999L));

        mockMvc.perform(get("/api/v1/campaign-activities/999")
                        .with(user("1").roles("ADMIN")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void invalidStatusTransitionReturnsConflictContract() throws Exception {
        when(campaignActivityService.changeCampaignActivityStatus(10L, CampaignActivityStatus.ENDED))
                .thenThrow(new BusinessConflictException("invalid status transition: DRAFT -> ENDED"));

        mockMvc.perform(patch("/api/v1/campaign-activities/10/status")
                        .param("status", "ENDED")
                        .with(user("1").roles("ADMIN")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("BUSINESS_CONFLICT"));
    }
}
