package com.axon.entry_service.controller;

import com.axon.entry_service.domain.behavior.UserBehaviorEvent;
import com.axon.entry_service.dto.BehaviorEventRequest;
import com.axon.entry_service.service.CampaignActivityMetaService;
import com.axon.entry_service.service.behavior.BehaviorEventPublisher;
import com.axon.entry_service.service.exception.BehaviorEventValidationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.userdetails.User;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class BehaviorEventControllerTest {

    private final BehaviorEventPublisher publisher = mock(BehaviorEventPublisher.class);
    private final CampaignActivityMetaService metaService = mock(CampaignActivityMetaService.class);
    private final BehaviorEventController controller = new BehaviorEventController(publisher, metaService);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void authenticatedUserIdComesFromPrincipalInsteadOfRequestBody() throws Exception {
        BehaviorEventRequest request = requestWithIdentity(999L, "browser-session");
        User principal = (User) User.withUsername("42").password("unused").roles("USER").build();

        controller.recordBehaviorEvent(request, principal, new MockHttpServletRequest());

        assertThat(publishedEvent().getUserId()).isEqualTo(42L);
    }

    @Test
    void anonymousRequestCannotPromoteBodyUserIdToAuthenticatedIdentity() throws Exception {
        BehaviorEventRequest request = requestWithIdentity(999L, "anonymous-session");

        controller.recordBehaviorEvent(request, null, new MockHttpServletRequest());

        UserBehaviorEvent event = publishedEvent();
        assertThat(event.getUserId()).isNull();
        assertThat(event.getSessionId()).isEqualTo("anonymous-session");
    }

    @Test
    void anonymousRequestRequiresSessionId() throws Exception {
        BehaviorEventRequest request = requestWithIdentity(999L, null);

        assertThatThrownBy(() -> controller.recordBehaviorEvent(request, null, new MockHttpServletRequest()))
                .isInstanceOf(BehaviorEventValidationException.class)
                .hasMessageContaining("sessionId");
    }

    private BehaviorEventRequest requestWithIdentity(Long userId, String sessionId) throws Exception {
        String json = """
                {
                  "eventName": "Product click",
                  "triggerType": "CLICK",
                  "userId": %s,
                  "sessionId": %s,
                  "properties": {}
                }
                """.formatted(userId, sessionId == null ? "null" : "\"" + sessionId + "\"");
        return objectMapper.readValue(json, BehaviorEventRequest.class);
    }

    private UserBehaviorEvent publishedEvent() {
        ArgumentCaptor<UserBehaviorEvent> captor = ArgumentCaptor.forClass(UserBehaviorEvent.class);
        verify(publisher).publish(captor.capture());
        return captor.getValue();
    }
}
