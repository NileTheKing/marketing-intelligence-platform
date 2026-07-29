package com.axon.core_service.repository;

import com.axon.core_service.domain.reconciliation.ReconciliationIssue;
import com.axon.core_service.domain.reconciliation.ReconciliationIssueStatus;
import com.axon.core_service.domain.reconciliation.ReconciliationIssueType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReconciliationIssueRepository extends JpaRepository<ReconciliationIssue, Long> {

    Optional<ReconciliationIssue> findByFingerprint(String fingerprint);

    List<ReconciliationIssue> findAllByStatusOrderByLastDetectedAtDesc(ReconciliationIssueStatus status);

    long countByStatusAndIssueType(ReconciliationIssueStatus status, ReconciliationIssueType issueType);
}
