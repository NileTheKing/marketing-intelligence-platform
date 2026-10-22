package com.axon.entry_service.controller;

import com.axon.entry_service.config.auth.JwtTokenProvider;
import com.axon.entry_service.config.auth.SecurityConfig;
import com.axon.entry_service.dto.payment.PaymentConfirmationResponse;
import com.axon.entry_service.service.entry.EntryApplicationService;
import com.axon.entry_service.service.entry.EntryUseCaseResult;
import com.axon.entry_service.service.entry.EntryUseCaseStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.stream.Stream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = EntryController.class)
@Import(SecurityConfig.class)
class EntryApiContractTest {

    private static final String VALID_REQUEST = """
            {
              "campaignActivityId": 1,
              "productId": 10,
              "quantity": 1
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EntryApplicationService entryApplicationService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @Test
    void anonymousEntryRequestIsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/v1/entries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_REQUEST))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(entryApplicationService);
    }

    @Test
    void invalidEntryRequestIsRejectedBeforeApplicationService() throws Exception {
        mockMvc.perform(post("/api/v1/entries")
                        .with(user("42").roles("USER"))
                        .header("Authorization", "Bearer test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId": 10, "quantity": 0}
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(entryApplicationService);
    }

    @Test
    void authenticatedValidEntryReturnsReservationContract() throws Exception {
        when(entryApplicationService.createEntry(any(), eq("Bearer test-token"), eq(42L)))
                .thenReturn(EntryUseCaseResult.ok(PaymentConfirmationResponse.success("reservation-token")));

        mockMvc.perform(post("/api/v1/entries")
                        .with(user("42").roles("USER"))
                        .header("Authorization", "Bearer test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_REQUEST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservationToken").value("reservation-token"));
    }

    @ParameterizedTest
    @MethodSource("failureStatuses")
    void applicationResultUsesStableHttpStatus(EntryUseCaseStatus useCaseStatus, int expectedStatus) throws Exception {
        when(entryApplicationService.createEntry(any(), eq("Bearer test-token"), eq(42L)))
                .thenReturn(EntryUseCaseResult.noBody(useCaseStatus));

        mockMvc.perform(post("/api/v1/entries")
                        .with(user("42").roles("USER"))
                        .header("Authorization", "Bearer test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_REQUEST))
                .andExpect(status().is(expectedStatus));
    }

    private static Stream<Arguments> failureStatuses() {
        return Stream.of(
                Arguments.of(EntryUseCaseStatus.NOT_FOUND, 404),
                Arguments.of(EntryUseCaseStatus.BAD_REQUEST, 400),
                Arguments.of(EntryUseCaseStatus.CONFLICT, 409),
                Arguments.of(EntryUseCaseStatus.GONE, 410),
                Arguments.of(EntryUseCaseStatus.INTERNAL_SERVER_ERROR, 500));
    }
}
