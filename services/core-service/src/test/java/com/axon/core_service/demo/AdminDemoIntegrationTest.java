package com.axon.core_service.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.axon.core_service.AbstractIntegrationTest;
import com.axon.core_service.config.auth.JwtTokenProvider;
import jakarta.servlet.http.Cookie;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class AdminDemoIntegrationTest extends AbstractIntegrationTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider jwtTokenProvider;

    @Test
    void visitorCanEnterAndBrowseAdminPagesWithReadOnlyToken() throws Exception {
        Cookie cookie = enterDemo();
        assertThat(jwtTokenProvider.getAuthentication(cookie.getValue()).getAuthorities())
                .extracting(authority -> authority.getAuthority()).containsExactly(AdminDemoAccess.AUTHORITY);
        mockMvc.perform(get("/admin").cookie(cookie))
                .andExpect(status().isFound()).andExpect(redirectedUrl("/admin/dashboard/overview"));
        for (String path : List.of("/admin/campaigns", "/admin/coupons", "/admin/events",
                "/admin/dashboard/overview", "/admin/incrementality")) {
            mockMvc.perform(get(path).cookie(cookie))
                    .andExpect(status().isOk()).andExpect(content().string(containsString("읽기 전용 데모")));
        }
        for (String path : List.of("/api/v1/campaigns", "/api/v1/campaign-activities", "/api/v1/coupons",
                "/api/v1/events")) {
            mockMvc.perform(get(path).cookie(cookie)).andExpect(status().isOk());
        }
    }

    @Test
    void demoCannotMutateEvenWhenCallingApisDirectly() throws Exception {
        Cookie cookie = enterDemo();
        mockMvc.perform(post("/api/v1/campaigns").cookie(cookie)
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"forbidden\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/v1/campaign-activities/1/status?status=ACTIVE").cookie(cookie))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/coupons/1").cookie(cookie)).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/campaign-activities/1/runs").cookie(cookie))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/products/1/reviews").cookie(cookie))
                .andExpect(status().isForbidden());
    }

    @Test
    void demoCannotReachPrivateReadsOrGetEndpointsWithSideEffects() throws Exception {
        Cookie cookie = enterDemo();
        for (String path : List.of("/admin/customers/1", "/admin/monitoring", "/api/v1/campaign-activities/1/entries",
                "/core/api/v1/reconciliation-issues", "/internal/v1/marketing-triage/cases/1",
                "/payment-success?productId=1&finalPrice=0", "/test/auth/token?userId=1")) {
            mockMvc.perform(get(path).cookie(cookie)).andExpect(status().isForbidden());
        }
    }

    @Test
    void demoCookieDoesNotReplaceExistingLoginAndExitClearsOnlyDemoCookie() throws Exception {
        String original = jwtTokenProvider.generateAccessToken(new UsernamePasswordAuthenticationToken("42", "",
                List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        Cookie demo = enterDemo();
        mockMvc.perform(get("/admin").cookie(new Cookie("accessToken", original), demo))
                .andExpect(status().isFound()).andExpect(redirectedUrl("/admin/dashboard/overview"));
        String clear = mockMvc.perform(get("/demo/exit").cookie(demo))
                .andExpect(status().isFound()).andExpect(redirectedUrl("/shop"))
                .andReturn().getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(clear).startsWith(AdminDemoAccess.COOKIE_NAME + "=").contains("Max-Age=0");
        mockMvc.perform(get("/admin").cookie(new Cookie("accessToken", original)))
                .andExpect(status().isForbidden());
    }

    private Cookie enterDemo() throws Exception {
        String header = mockMvc.perform(get("/demo/admin").secure(true))
                .andExpect(status().isFound()).andExpect(redirectedUrl("/admin"))
                .andReturn().getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(header).contains("HttpOnly", "Secure", "SameSite=Lax", "Max-Age=1800");
        return new Cookie(AdminDemoAccess.COOKIE_NAME, header.split(";", 2)[0].split("=", 2)[1]);
    }
}
