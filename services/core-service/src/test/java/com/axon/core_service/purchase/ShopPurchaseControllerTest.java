package com.axon.core_service.purchase;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.axon.core_service.domain.purchase.PurchaseStatus;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

class ShopPurchaseControllerTest {
    private final ShopPurchaseApplicationService service = mock(ShopPurchaseApplicationService.class);
    private MockMvc mvc;
    private final UsernamePasswordAuthenticationToken auth =
            new UsernamePasswordAuthenticationToken("7", "", java.util.List.of());
    private final ShopPurchaseResponse response = new ShopPurchaseResponse(9L, 3L, 1,
            new BigDecimal("70.00"), PurchaseStatus.CONFIRMED);

    @BeforeEach void setUp() {
        var validator = new LocalValidatorFactoryBean(); validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(new ShopPurchaseController(service)).setValidator(validator).build();
    }
    @Test void createsAndReturnsStoredResultOnRetry() throws Exception {
        when(service.confirm(eq(7L), any())).thenReturn(new ShopPurchaseResult(true, response),
                new ShopPurchaseResult(false, response));
        String body = "{\"requestId\":\"" + UUID.randomUUID() + "\",\"productId\":3}";
        mvc.perform(post("/api/v1/shop-purchases").principal(auth).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(header().string("Location", "/api/v1/shop-purchases/9"))
                .andExpect(jsonPath("$.paidAmount").value(70));
        mvc.perform(post("/api/v1/shop-purchases").principal(auth).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.purchaseId").value(9));
    }
    @Test void rejectsUntrustedAndUnsupportedFields() throws Exception {
        for (String extra : java.util.List.of("\"finalPrice\":1", "\"userId\":4", "\"quantity\":1")) {
            String body = "{\"requestId\":\"" + UUID.randomUUID() + "\",\"productId\":3," + extra + "}";
            mvc.perform(post("/api/v1/shop-purchases").principal(auth).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }
    @Test void ownershipErrorsHaveNotFoundResponse() throws Exception {
        when(service.getPurchase(7L, 9L)).thenThrow(new ShopPurchaseException(
                org.springframework.http.HttpStatus.NOT_FOUND, "PURCHASE_NOT_FOUND"));
        mvc.perform(get("/api/v1/shop-purchases/9").principal(auth))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PURCHASE_NOT_FOUND"));
    }
}
