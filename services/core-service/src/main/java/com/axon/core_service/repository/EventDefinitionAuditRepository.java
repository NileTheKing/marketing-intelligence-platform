package com.axon.core_service.repository;

import com.axon.core_service.domain.event.EventDefinitionAudit;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventDefinitionAuditRepository extends JpaRepository<EventDefinitionAudit, Long> {

    List<EventDefinitionAudit> findByEventIdOrderByIdAsc(Long eventId);
}
