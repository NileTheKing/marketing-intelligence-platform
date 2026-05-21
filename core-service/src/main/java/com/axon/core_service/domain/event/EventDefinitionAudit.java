package com.axon.core_service.domain.event;

import com.axon.core_service.domain.common.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Map;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(name = "event_definition_audits")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EventDefinitionAudit extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long eventId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EventDefinitionAuditAction action;

    @Column(nullable = false)
    private String eventName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EventStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TriggerType triggerType;

    @Convert(converter = TriggerConditionPayloadConverter.class)
    @Column(nullable = false, columnDefinition = "TEXT")
    private Map<String, Object> triggerPayload;

    @Builder
    private EventDefinitionAudit(Long eventId,
                                 EventDefinitionAuditAction action,
                                 String eventName,
                                 EventStatus status,
                                 TriggerType triggerType,
                                 Map<String, Object> triggerPayload) {
        this.eventId = eventId;
        this.action = action;
        this.eventName = eventName;
        this.status = status;
        this.triggerType = triggerType;
        this.triggerPayload = triggerPayload;
    }

    public static EventDefinitionAudit from(Event event, EventDefinitionAuditAction action) {
        return EventDefinitionAudit.builder()
                .eventId(event.getId())
                .action(action)
                .eventName(event.getName())
                .status(event.getStatus())
                .triggerType(event.getTriggerCondition().getTriggerType())
                .triggerPayload(event.getTriggerCondition().getPayload())
                .build();
    }
}
