package com.axon.core_service.config.auth;

import com.axon.core_service.config.TestSecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

@WebMvcTest(controllers = SecurityProbeController.class)
@Import({SecurityConfig.class, TestSecurityConfig.class, SecurityProbeController.class})
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

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
        mockMvc.perform(get("/api/v1/events/active"))
                .andExpect(status().isOk());
    }

    @Test
    void anonymousBrowserRequestStillStartsOAuthLogin() throws Exception {
        mockMvc.perform(get("/products/1"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("http://localhost/oauth2/authorization/naver"));
    }

    @Test
    void anonymousCallerCannotReachProtectedApis() throws Exception {
        mockMvc.perform(post("/api/v1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/dashboard/overview"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/validation"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/campaign-activities/1/entries"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void regularUserCannotReachAdminApis() throws Exception {
        mockMvc.perform(get("/admin").with(user("1").roles("USER")))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/events")
                        .with(user("1").roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/files/upload").with(user("1").roles("USER")))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/core/api/v1/reconciliation-issues")
                        .with(user("1").roles("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void regularUserCanUseValidationApi() throws Exception {
        mockMvc.perform(get("/api/v1/validation").with(user("1").roles("USER")))
                .andExpect(status().isOk());
    }

    @Test
    void systemTokenCanReadOnlyCampaignActivityMetadata() throws Exception {
        mockMvc.perform(get("/api/v1/campaign-activities/1")
                        .with(user("0").authorities(() -> "ROLE_SYSTEM")))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/campaign-activities/1/entries")
                        .with(user("0").authorities(() -> "ROLE_SYSTEM")))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/campaign-activities/count")
                        .with(user("0").authorities(() -> "ROLE_SYSTEM")))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanReachManagementApis() throws Exception {
        mockMvc.perform(get("/admin").with(user("1").roles("ADMIN")))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/events")
                        .with(user("1").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/files/upload").with(user("1").roles("ADMIN")))
                .andExpect(status().isOk());

        mockMvc.perform(get("/core/api/v1/reconciliation-issues")
                        .with(user("1").roles("ADMIN")))
                .andExpect(status().isOk());
    }

}
