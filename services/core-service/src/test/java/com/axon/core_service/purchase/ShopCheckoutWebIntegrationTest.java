package com.axon.core_service.purchase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.axon.core_service.AbstractIntegrationTest;
import com.axon.core_service.catalog.ProductRepository;
import com.axon.core_service.catalog.domain.Product;
import com.axon.core_service.config.auth.JwtTokenProvider;
import com.axon.core_service.repository.PurchaseRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class ShopCheckoutWebIntegrationTest extends AbstractIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private JwtTokenProvider tokens;
    @Autowired private ProductRepository products;
    @Autowired private PurchaseRepository purchases;
    @Autowired private ObjectMapper json;

    @Test
    void cookieCheckoutIssuesCsrfAndOnlyConfirmedOwnedPurchaseCanBeViewed() throws Exception {
        var product = products.save(new Product("checkout web", 5L, new BigDecimal("10000.00"), "TECH"));
        var login = login(90701L);
        var page = mvc.perform(get("/checkout").param("productId", product.getId().toString()).cookie(login))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("shop-checkout.js")))
                .andExpect(content().string(containsString("id=\"checkout-action-label\"")))
                .andExpect(content().string(containsString("실제 결제나 배송은 진행되지 않습니다")))
                .andReturn();
        var csrfToken = Pattern.compile("data-csrf-token=\"([^\"]+)\"")
                .matcher(page.getResponse().getContentAsString());
        assertThat(csrfToken.find()).as("checkout must render a CSRF token").isTrue();
        var session = (MockHttpSession) page.getRequest().getSession(false);
        assertThat(session).isNotNull();
        String request = "{\"requestId\":\"" + UUID.randomUUID() + "\",\"productId\":" + product.getId() + "}";
        mvc.perform(post("/api/v1/shop-purchases").cookie(login).session(session)
                .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/shop-purchases").cookie(login).session(session)
                .header("X-CSRF-TOKEN", "invalid").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isForbidden());
        var confirmed = mvc.perform(post("/api/v1/shop-purchases").cookie(login).session(session)
                .header("X-CSRF-TOKEN", csrfToken.group(1)).contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.paidAmount").value(10000.0)).andReturn();
        long id = json.readTree(confirmed.getResponse().getContentAsString()).get("purchaseId").asLong();
        mvc.perform(post("/api/v1/shop-purchases").cookie(login).session(session)
                .header("X-CSRF-TOKEN", csrfToken.group(1)).contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk()).andExpect(jsonPath("$.purchaseId").value(id));
        long count = purchases.count();
        for (int i = 0; i < 2; i++) {
            mvc.perform(get("/payment/success").param("purchaseId", String.valueOf(id)).cookie(login))
                    .andExpect(status().isOk()).andExpect(view().name("payment-success"))
                    .andExpect(content().string(containsString("실제 결제와 배송은 진행되지 않습니다")));
        }
        mvc.perform(get("/payment/success").param("purchaseId", String.valueOf(id)).cookie(login(90702L)))
                .andExpect(status().isNotFound()).andExpect(view().name("purchase-error"));
        mvc.perform(get("/api/v1/shop-purchases/" + id).cookie(login(90702L)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/payment/success").param("productId", product.getId().toString())
                .param("finalPrice", "0").cookie(login)).andExpect(status().isBadRequest());
        assertThat(purchases.count()).isEqualTo(count);
    }

    @Test
    void existingLogoutLinkStillLogsOutWithGet() throws Exception {
        mvc.perform(get("/logout").cookie(login(90701L)))
                .andExpect(status().isFound()).andExpect(redirectedUrl("/shop"))
                .andExpect(cookie().maxAge("accessToken", 0));
    }

    @Test
    void csrfDoesNotReplaceAuthenticationOrDemoWriteProtection() throws Exception {
        String request = "{\"requestId\":\"" + UUID.randomUUID() + "\",\"productId\":1}";
        mvc.perform(post("/api/v1/shop-purchases").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        var demo = mvc.perform(get("/demo/admin")).andReturn().getResponse().getCookie("adminDemoToken");
        assertThat(demo).isNotNull();
        mvc.perform(post("/api/v1/shop-purchases").cookie(demo).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isForbidden());
    }

    private Cookie login(long userId) {
        var authentication = new UsernamePasswordAuthenticationToken(String.valueOf(userId), "",
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        return new Cookie("accessToken", tokens.generateAccessToken(authentication));
    }
}
