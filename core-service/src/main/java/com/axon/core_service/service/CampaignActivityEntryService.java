package com.axon.core_service.service;

import com.axon.core_service.aop.DistributedLock;
import com.axon.core_service.domain.campaignactivity.CampaignActivity;
import com.axon.core_service.domain.campaignactivityentry.CampaignActivityEntry;
import com.axon.core_service.domain.campaignactivityentry.CampaignActivityEntryStatus;
import com.axon.core_service.domain.dto.purchase.PurchaseInfoDto;
import com.axon.core_service.domain.purchase.PurchaseType;
import com.axon.core_service.repository.CampaignActivityEntryRepository;
import com.axon.messaging.dto.CampaignActivityKafkaProducerDto;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@Transactional
@RequiredArgsConstructor
public class CampaignActivityEntryService {

    private final CampaignActivityEntryRepository campaignActivityEntryRepository;
    private final ApplicationEventPublisher eventPublisher;

    @DistributedLock(key = "'lock:entry:' + #campaignActivity.id + ':' + #dto.userId", waitTime = 3, leaseTime = 5)
    @Transactional
    public CampaignActivityEntry upsertEntry(CampaignActivity campaignActivity,
            CampaignActivityKafkaProducerDto dto,
            CampaignActivityEntryStatus nextStatus,
            boolean processed) {
        return upsertEntryInternal(campaignActivity, dto, nextStatus, processed);
    }

    private CampaignActivityEntry upsertEntryInternal(CampaignActivity campaignActivity,
            CampaignActivityKafkaProducerDto dto,
            CampaignActivityEntryStatus nextStatus,
            boolean processed) {
        Instant requestedAt = Optional.ofNullable(dto.getTimestamp())
                .map(Instant::ofEpochMilli)
                .orElseGet(Instant::now);

        CampaignActivityEntry entry = campaignActivityEntryRepository
                .findByCampaignActivity_IdAndUserId(campaignActivity.getId(), dto.getUserId())
                .orElseGet(() -> CampaignActivityEntry.create(
                        campaignActivity,
                        dto.getUserId(),
                        dto.getProductId(),
                        requestedAt));

        entry.updateProduct(dto.getProductId());
        entry.updateStatus(nextStatus);
        if (processed) {
            entry.markProcessedNow();
        }

        CampaignActivityEntry saved = campaignActivityEntryRepository.save(entry);

        if (nextStatus == CampaignActivityEntryStatus.APPROVED
                && campaignActivity.getActivityType().isPurchaseRelated()) {
            eventPublisher.publishEvent(new PurchaseInfoDto(
                    campaignActivity.getCampaignId(),
                    campaignActivity.getId(),
                    dto.getUserId(),
                    dto.getProductId(),
                    dto.occurredAt(),
                    PurchaseType.CAMPAIGNACTIVITY,
                    campaignActivity.getPrice(),
                    (int) (dto.getQuantity() != null ? dto.getQuantity().longValue() : 1L),
                    requestedAt));
        }

        return saved;
    }

    @Transactional
    public void upsertBatch(
            Map<Long, CampaignActivity> activityMap,
            List<CampaignActivityKafkaProducerDto> messages,
            CampaignActivityEntryStatus status) {

        if (messages.isEmpty()) {
            return;
        }

        log.info("Bulk upsert: {} entries", messages.size());

        List<Long> activityIds = messages.stream()
                .map(CampaignActivityKafkaProducerDto::getCampaignActivityId)
                .distinct()
                .toList();

        List<Long> userIds = messages.stream()
                .map(CampaignActivityKafkaProducerDto::getUserId)
                .distinct()
                .toList();

        List<CampaignActivityEntry> existingEntries = campaignActivityEntryRepository.findByActivityIdsAndUserIds(activityIds, userIds);

        Map<String, CampaignActivityEntry> existingMap = existingEntries.stream()
                .collect(Collectors.toMap(
                        entry -> entry.getCampaignActivity().getId() + ":" + entry.getUserId(),
                        entry -> entry
                ));

        List<CampaignActivityEntry> toSave = new ArrayList<>();
        List<PurchaseInfoDto> purchaseEvents = new ArrayList<>();

        for (CampaignActivityKafkaProducerDto dto : messages) {
            CampaignActivity activity = activityMap.get(dto.getCampaignActivityId());
            if (activity == null) {
                continue;
            }

            String key = activity.getId() + ":" + dto.getUserId();
            Instant requestedAt = Optional.ofNullable(dto.getTimestamp())
                    .map(Instant::ofEpochMilli)
                    .orElseGet(Instant::now);

            CampaignActivityEntry entry = existingMap.getOrDefault(key,
                    CampaignActivityEntry.create(
                            activity,
                            dto.getUserId(),
                            dto.getProductId(),
                            requestedAt
                    ));

            entry.updateProduct(dto.getProductId());
            entry.updateStatus(status);
            entry.markProcessedNow();
            toSave.add(entry);

            boolean isApproved = (status == CampaignActivityEntryStatus.APPROVED);
            boolean isPurchaseRelated = activity.getActivityType().isPurchaseRelated();
            boolean isNewEntry = !existingMap.containsKey(key);

            if (isApproved && isPurchaseRelated && isNewEntry) {
                purchaseEvents.add(new PurchaseInfoDto(
                        activity.getCampaignId(),
                        activity.getId(),
                        dto.getUserId(),
                        dto.getProductId(),
                        dto.occurredAt(),
                        PurchaseType.CAMPAIGNACTIVITY,
                        activity.getPrice(),
                        (int) (dto.getQuantity() != null ? dto.getQuantity().longValue() : 1L),
                        requestedAt
                ));
            }
        }

        if (!toSave.isEmpty()) {
            try {
                campaignActivityEntryRepository.saveAll(toSave);
                log.info("[Entry] Saved {} entries successfully", toSave.size());
            } catch (Exception e) {
                log.warn("[Entry] Batch failed, retrying individually. Error: {}", e.getMessage());
                int savedCount = retryEntriesIndividually(toSave);
                log.info("[Entry] Recovered {}/{} entries via individual retry", savedCount, toSave.size());
            }
        }

        if (!purchaseEvents.isEmpty()) {
            purchaseEvents.forEach(eventPublisher::publishEvent);
            log.info("[Purchase Event] Published {} events", purchaseEvents.size());
        }
    }

    private int retryEntriesIndividually(List<CampaignActivityEntry> entries) {
        int count = 0;
        for (CampaignActivityEntry entry : entries) {
            try {
                saveSingleEntryInNewTransaction(entry);
                count++;
            } catch (Exception ex) {
                log.debug("Skipping failed entry: userId={}", entry.getUserId());
            }
        }
        return count;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveSingleEntryInNewTransaction(CampaignActivityEntry entry) {
        campaignActivityEntryRepository.save(entry);
    }
}
