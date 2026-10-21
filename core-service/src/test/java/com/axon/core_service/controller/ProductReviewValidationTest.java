package com.axon.core_service.controller;

import com.axon.core_service.domain.dto.review.ProductReviewCreateRequest;
import com.axon.core_service.domain.dto.review.ProductReviewResponse;
import com.axon.core_service.domain.user.CustomOAuth2User;
import com.axon.core_service.config.TestSecurityConfig;
import com.axon.core_service.config.auth.CustomLogoutHandler;
import com.axon.core_service.config.auth.CustomOAuth2UserService;
import com.axon.core_service.config.auth.OAuth2AuthenticationSuccessHandler;
import com.axon.core_service.config.auth.SecurityConfig;
import com.axon.core_service.service.ProductReviewService;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ProductReviewController.class)
@Import({SecurityConfig.class, TestSecurityConfig.class})
class ProductReviewValidationTest {
    @Autowired private MockMvc mockMvc;
    @MockitoBean private ProductReviewService productReviewService;
    @MockitoBean private CustomOAuth2UserService customOAuth2UserService;
    @MockitoBean private OAuth2AuthenticationSuccessHandler oAuth2AuthenticationSuccessHandler;
    @MockitoBean private CustomLogoutHandler customLogoutHandler;
    @MockitoBean private JpaMetamodelMappingContext jpaMetamodelMappingContext;

    @Test
    void createsUsingAuthenticatedUserIdWithoutUnsupportedLocationHeader() throws Exception {
        var principal = new CustomOAuth2User(List.of(new SimpleGrantedAuthority("ROLE_USER")),
                java.util.Map.of("name", "test"), "name", 73L, "Test");
        when(productReviewService.create(org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.eq(73L),
                org.mockito.ArgumentMatchers.any(ProductReviewCreateRequest.class)))
                .thenReturn(new ProductReviewResponse(11L, 5, "good", LocalDateTime.parse("2026-10-16T10:00:00")));

        mockMvc.perform(post("/api/v1/products/7/reviews")
                        .with(authentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"purchaseId\":99,\"rating\":5,\"content\":\" good \"}"))
                .andExpect(status().isCreated())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().doesNotExist("Location"));
        verify(productReviewService).create(org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.eq(73L),
                org.mockito.ArgumentMatchers.any(ProductReviewCreateRequest.class));
    }

    private UsernamePasswordAuthenticationToken testAuthentication() {
        var principal = new CustomOAuth2User(List.of(new SimpleGrantedAuthority("ROLE_USER")),
                java.util.Map.of("name", "test"), "name", 73L, "Test");
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    @Test
    void rejectsMissingPurchaseRatingOutsideRangeAndBlankContent() throws Exception {
        for (String body : new String[] {
                "{\"purchaseId\":1,\"rating\":0,\"content\":\"good\"}",
                "{\"purchaseId\":1,\"rating\":6,\"content\":\"good\"}",
                "{\"rating\":5,\"content\":\"good\"}",
                "{\"purchaseId\":1,\"rating\":5,\"content\":\"  \"}"
        }) {
            mockMvc.perform(post("/api/v1/products/7/reviews")
                            .with(authentication(testAuthentication()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }
    }
}
