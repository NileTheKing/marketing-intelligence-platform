package com.axon.core_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.axon.core_service.domain.dto.event.EventDefinitionResponse;
import com.axon.core_service.domain.dto.event.EventRequest;
import com.axon.core_service.domain.event.Event;
import com.axon.core_service.domain.event.EventDefinitionAuditAction;
import com.axon.core_service.domain.event.EventStatus;
import com.axon.core_service.domain.event.TriggerType;
import com.axon.core_service.repository.EventDefinitionAuditRepository;
import com.axon.core_service.repository.EventRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

@DataJpaTest
@Import(EventService.class)
class EventServiceTest {

    @Autowired
    private EventService eventService;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private EventDefinitionAuditRepository eventDefinitionAuditRepository;

    @BeforeEach
    void setUp() {
        eventDefinitionAuditRepository.deleteAll();
        eventRepository.deleteAll();

        eventRepository.save(Event.builder()
                .name("Active Page View")
                .description("page view event")
                .status(EventStatus.ACTIVE)
                .triggerCondition(Event.TriggerCondition.of(TriggerType.PAGE_VIEW, Map.of("urlPattern", "/products/*")))
                .build());

        eventRepository.save(Event.builder()
                .name("Inactive Click")
                .description("click event")
                .status(EventStatus.INACTIVE)
                .triggerCondition(Event.TriggerCondition.of(TriggerType.CLICK, Map.of("selector", "#cta")))
                .build());

        eventRepository.save(Event.builder()
                .name("Active Click")
                .description("active click event")
                .status(EventStatus.ACTIVE)
                .triggerCondition(Event.TriggerCondition.of(TriggerType.CLICK, Map.of("selector", "#submit")))
                .build());
    }

    @Test
    @DisplayName("트리거 타입이 주어지면 해당 ACTIVE 이벤트만 반환한다")
    void getActiveEventDefinitions_filterByTriggerType() {
        List<EventDefinitionResponse> responses = eventService.getActiveEventDefinitions(TriggerType.CLICK);

        assertThat(responses)
                .hasSize(1)
                .first()
                .extracting(EventDefinitionResponse::getName)
                .isEqualTo("Active Click");
    }

    @Test
    @DisplayName("트리거 타입이 없으면 모든 ACTIVE 이벤트를 반환한다")
    void getActiveEventDefinitions_allActive() {
        List<EventDefinitionResponse> responses = eventService.getActiveEventDefinitions(null);

        assertThat(responses)
                .hasSize(2)
                .extracting(EventDefinitionResponse::getName)
                .containsExactly("Active Page View", "Active Click");
    }

    @Test
    @DisplayName("CLICK 이벤트는 selector 또는 trackId 없이 생성할 수 없다")
    void createClickEvent_requiresSelectorOrTrackId() {
        EventRequest request = EventRequest.builder()
                .name("Invalid Click")
                .description("missing click target")
                .status(EventStatus.ACTIVE)
                .triggerType(TriggerType.CLICK)
                .triggerPayload(Map.of())
                .build();

        assertThatThrownBy(() -> eventService.createEvent(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CLICK event requires selector or trackId");
    }

    @Test
    @DisplayName("CLICK 이벤트는 trackId만으로도 생성할 수 있다")
    void createClickEvent_acceptsTrackId() {
        EventRequest request = EventRequest.builder()
                .name("Track Id Click")
                .description("stable click target")
                .status(EventStatus.ACTIVE)
                .triggerType(TriggerType.CLICK)
                .triggerPayload(Map.of("trackId", "purchase-button"))
                .build();

        eventService.createEvent(request);

        List<EventDefinitionResponse> responses = eventService.getActiveEventDefinitions(TriggerType.CLICK);
        assertThat(responses)
                .extracting(EventDefinitionResponse::getName)
                .contains("Track Id Click");

        Event created = eventRepository.findAllByTriggerCondition_TriggerTypeAndStatusOrderByIdAsc(
                TriggerType.CLICK, EventStatus.ACTIVE).stream()
                .filter(event -> event.getName().equals("Track Id Click"))
                .findFirst()
                .orElseThrow();
        assertThat(eventDefinitionAuditRepository.findByEventIdOrderByIdAsc(created.getId()))
                .hasSize(1)
                .first()
                .extracting("action")
                .isEqualTo(EventDefinitionAuditAction.CREATED);
    }

    @Test
    @DisplayName("PAGE_VIEW urlPattern은 값이 있으면 공백일 수 없다")
    void createPageViewEvent_rejectsBlankUrlPattern() {
        EventRequest request = EventRequest.builder()
                .name("Invalid Page View")
                .description("blank url pattern")
                .status(EventStatus.ACTIVE)
                .triggerType(TriggerType.PAGE_VIEW)
                .triggerPayload(Map.of("urlPattern", " "))
                .build();

        assertThatThrownBy(() -> eventService.createEvent(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PAGE_VIEW urlPattern must not be blank");
    }
}
