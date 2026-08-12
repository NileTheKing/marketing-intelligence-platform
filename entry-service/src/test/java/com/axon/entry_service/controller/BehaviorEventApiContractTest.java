package com.axon.entry_service.controller;

import com.axon.entry_service.config.auth.JwtTokenProvider;
import com.axon.entry_service.config.auth.SecurityConfig;
import com.axon.entry_service.domain.behavior.UserBehaviorEvent;
import com.axon.entry_service.service.CampaignActivityMetaService;
import com.axon.entry_service.service.behavior.BehaviorEventPublisher;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = BehaviorEventController.class)
@Import({SecurityConfig.class, BehaviorEventExceptionHandler.class})
class BehaviorEventApiContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BehaviorEventPublisher publisher;

    @MockitoBean
    private CampaignActivityMetaService metaService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @Test
    void anonymousEventRequiresSessionAndIgnoresBodyUserId() throws Exception {
        mockMvc.perform(post("/api/v1/behavior-events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "eventName": "Product click",
                                  "triggerType": "CLICK",
                                  "userId": 999,
                                  "sessionId": "browser-session",
                                  "properties": {}
                                }
                                """))
                .andExpect(status().isAccepted());

        UserBehaviorEvent event = publishedEvent();
        assertThat(event.getUserId()).isNull();
        assertThat(event.getSessionId()).isEqualTo("browser-session");
    }

    @Test
    void authenticatedIdentityComesFromSecurityContext() throws Exception {
        mockMvc.perform(post("/api/v1/behavior-events")
                        .with(user("42").roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "triggerType": "CLICK",
                                  "userId": 999,
                                  "properties": {}
                                }
                                """))
                .andExpect(status().isAccepted());

        assertThat(publishedEvent().getUserId()).isEqualTo(42L);
    }

    @Test
    void anonymousEventWithoutSessionReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/behavior-events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"triggerType": "CLICK", "properties": {}}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Anonymous behavior event requires sessionId."));

        verifyNoInteractions(publisher);
    }

    @Test
    void missingTriggerTypeIsRejectedBeforePublish() throws Exception {
        mockMvc.perform(post("/api/v1/behavior-events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sessionId": "browser-session", "properties": {}}
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(publisher);
    }

    private UserBehaviorEvent publishedEvent() {
        ArgumentCaptor<UserBehaviorEvent> captor = ArgumentCaptor.forClass(UserBehaviorEvent.class);
        verify(publisher).publish(captor.capture());
        return captor.getValue();
    }
}
