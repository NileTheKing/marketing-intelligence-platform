package com.axon.core_service.repository;

import com.axon.core_service.domain.marketing.AudienceSegment;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AudienceSegmentRepository extends JpaRepository<AudienceSegment, Long> {
}
