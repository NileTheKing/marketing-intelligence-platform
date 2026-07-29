package com.axon.core_service.service.reconciliation;

import com.axon.core_service.domain.purchase.Purchase;
import com.axon.core_service.domain.reconciliation.ReconciliationIssue;
import com.axon.core_service.domain.reconciliation.ReconciliationIssueStatus;
import com.axon.core_service.domain.reconciliation.ReconciliationIssueType;
import com.axon.core_service.observability.CorePipelineMetrics;
import com.axon.core_service.repository.ReconciliationIssueRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReconciliationIssueService {

    private final ReconciliationIssueRepository issueRepository;
    private final CorePipelineMetrics pipelineMetrics;

    @PostConstruct
    public void initializeOpenIssueMetrics() {
        for (ReconciliationIssueType issueType : ReconciliationIssueType.values()) {
            refreshOpenIssueCount(issueType);
        }
    }

    @Transactional
    public ReconciliationIssue detectRedisPurchaseCountMismatch(Long campaignActivityId,
                                                                  long redisAdmissionCount,
                                                                  long persistedPurchaseCount) {
        return upsert(
                ReconciliationIssueType.REDIS_PURCHASE_COUNT_MISMATCH,
                campaignActivityId,
                null,
                null,
                redisAdmissionCount,
                persistedPurchaseCount,
                "Redis FCFS admission count and persisted Purchase count differ"
        );
    }

    @Transactional
    public ReconciliationIssue detectGhostPurchase(Purchase purchase) {
        return upsert(
                ReconciliationIssueType.GHOST_PURCHASE,
                purchase.getCampaignActivityId(),
                purchase.getId(),
                purchase.getUserId(),
                null,
                null,
                "Purchase has no matching CampaignActivityEntry"
        );
    }

    @Transactional(readOnly = true)
    public List<ReconciliationIssue> getOpenIssues() {
        return issueRepository.findAllByStatusOrderByLastDetectedAtDesc(ReconciliationIssueStatus.OPEN);
    }

    @Transactional
    public ReconciliationIssue acknowledge(Long issueId, String note) {
        ReconciliationIssue issue = getIssue(issueId);
        issue.acknowledge(requireNote(note), LocalDateTime.now());
        refreshOpenIssueCount(issue.getIssueType());
        return issue;
    }

    @Transactional
    public ReconciliationIssue resolve(Long issueId, String note) {
        ReconciliationIssue issue = getIssue(issueId);
        issue.resolve(requireNote(note), LocalDateTime.now());
        refreshOpenIssueCount(issue.getIssueType());
        return issue;
    }

    private ReconciliationIssue upsert(ReconciliationIssueType issueType, Long campaignActivityId,
                                       Long purchaseId, Long userId, Long expectedValue,
                                       Long observedValue, String evidence) {
        String fingerprint = fingerprint(issueType, campaignActivityId, purchaseId);
        LocalDateTime now = LocalDateTime.now();

        ReconciliationIssue issue = issueRepository.findByFingerprint(fingerprint)
                .map(existing -> {
                    boolean reopened = existing.refreshDetection(expectedValue, observedValue, evidence, now);
                    recordDetection(existing, reopened, now);
                    return existing;
                })
                .orElseGet(() -> {
                    ReconciliationIssue created = ReconciliationIssue.open(
                            issueType, fingerprint, campaignActivityId, purchaseId, userId,
                            expectedValue, observedValue, evidence, now);
                    recordDetection(created, true, now);
                    return issueRepository.save(created);
                });

        refreshOpenIssueCount(issueType);
        log.warn("[ReconciliationIssue] detected: type={}, issueId={}, activityId={}, purchaseId={}",
                issueType, issue.getId(), campaignActivityId, purchaseId);
        return issue;
    }

    private void recordDetection(ReconciliationIssue issue, boolean newOccurrence, LocalDateTime now) {
        long ageSeconds = Math.max(0, Duration.between(issue.getFirstDetectedAt(), now).toSeconds());
        pipelineMetrics.recordReconciliationIssueDetection(issue.getIssueType(), newOccurrence, ageSeconds);
    }

    private ReconciliationIssue getIssue(Long issueId) {
        return issueRepository.findById(issueId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Reconciliation issue not found: " + issueId));
    }

    private String fingerprint(ReconciliationIssueType issueType, Long campaignActivityId, Long purchaseId) {
        return issueType.name() + ":" + campaignActivityId + ":" + (purchaseId != null ? purchaseId : "-");
    }

    private String requireNote(String note) {
        if (note == null || note.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Acknowledge or resolve note must not be blank");
        }
        return note;
    }

    private void refreshOpenIssueCount(ReconciliationIssueType issueType) {
        pipelineMetrics.setOpenReconciliationIssueCount(
                issueType,
                issueRepository.countByStatusAndIssueType(ReconciliationIssueStatus.OPEN, issueType));
    }
}
