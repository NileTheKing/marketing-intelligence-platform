package com.axon.core_service.commandprocessing;

import com.axon.core_service.campaign.domain.CampaignActivity;
import com.axon.core_service.entry.domain.CampaignActivityEntry;
import com.axon.core_service.entry.domain.CampaignActivityEntryStatus;
import com.axon.core_service.domain.purchase.Purchase;
import com.axon.core_service.domain.purchase.PurchaseStatus;
import com.axon.core_service.domain.purchase.PurchaseType;
import com.axon.core_service.entry.CampaignActivityEntryRepository;
import com.axon.core_service.campaign.CampaignActivityRepository;
import com.axon.core_service.repository.PurchaseRepository;
import com.axon.core_service.service.ActivityUserKey;
import com.axon.messaging.dto.CampaignActivityKafkaProducerDto;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class FcfsLedgerPersistenceService {

    private final CampaignActivityRepository campaignActivityRepository;
    private final CampaignActivityEntryRepository entryRepository;
    private final PurchaseRepository purchaseRepository;
    private final JdbcTemplate jdbcTemplate;

    @Transactional
    public List<LedgerResult> persistBatch(List<CampaignActivityKafkaProducerDto> messages) {
        if (messages.isEmpty()) {
            return List.of();
        }

        List<Long> activityIds = messages.stream()
                .map(CampaignActivityKafkaProducerDto::getCampaignActivityId).distinct().toList();
        List<Long> userIds = messages.stream()
                .map(CampaignActivityKafkaProducerDto::getUserId).distinct().toList();
        Map<Long, CampaignActivity> activities = campaignActivityRepository.findAllById(activityIds).stream()
                .collect(java.util.stream.Collectors.toMap(CampaignActivity::getId, activity -> activity));
        if (activities.size() != activityIds.size()) {
            throw new IllegalArgumentException("존재하지 않는 캠페인 활동이 포함되어 있습니다");
        }

        Map<ActivityUserKey, CampaignActivityEntry> entries = entryRepository
                .findByActivityIdsAndUserIds(activityIds, userIds).stream()
                .collect(java.util.stream.Collectors.toMap(
                        entry -> new ActivityUserKey(entry.getCampaignActivity().getId(), entry.getUserId()), entry -> entry));
        Map<ActivityUserKey, Purchase> purchases = purchaseRepository
                .findByCampaignActivityIdsAndUserIds(activityIds, userIds).stream()
                .collect(java.util.stream.Collectors.toMap(
                        purchase -> new ActivityUserKey(purchase.getCampaignActivityId(), purchase.getUserId()), purchase -> purchase));

        List<CampaignActivityEntry> newEntries = new java.util.ArrayList<>();
        List<Purchase> newPurchases = new java.util.ArrayList<>();
        List<LedgerResult> results = new java.util.ArrayList<>();
        for (CampaignActivityKafkaProducerDto dto : messages) {
            CampaignActivity activity = activities.get(dto.getCampaignActivityId());
            ActivityUserKey key = new ActivityUserKey(activity.getId(), dto.getUserId());
            CampaignActivityEntry entry = entries.get(key);
            if (entry == null) {
                entry = CampaignActivityEntry.create(activity, dto.getUserId(), dto.getProductId(), requestedAt(dto));
                entries.put(key, entry);
                newEntries.add(entry);
            }
            entry.updateProduct(dto.getProductId());
            entry.updateStatus(CampaignActivityEntryStatus.APPROVED);
            entry.markProcessedNow();

            Purchase purchase = purchases.get(key);
            boolean purchaseCreated = purchase == null;
            if (purchaseCreated) {
                Instant purchasedAt = Optional.ofNullable(dto.occurredAt()).orElseGet(Instant::now);
                purchase = new Purchase(dto.getUserId(), dto.getProductId(), activity.getId(),
                        PurchaseType.CAMPAIGNACTIVITY, activity.getPrice(), quantity(dto), purchasedAt);
                purchases.put(key, purchase);
                newPurchases.add(purchase);
            }
            results.add(new LedgerResult(dto, activity.getCampaignId(), purchaseCreated,
                    purchase.getPurchaseAt().atZone(java.time.ZoneId.of("Asia/Seoul")).toInstant()));
        }

        insertEntries(newEntries);
        insertPurchases(newPurchases);
        return results;
    }

    private void insertEntries(List<CampaignActivityEntry> entries) {
        if (entries.isEmpty()) {
            return;
        }

        String values = String.join(",", java.util.Collections.nCopies(entries.size(), "(?,?,?,?,?,?,?,?,?)"));
        String sql = "INSERT INTO campaign_activity_entries "
                + "(campaign_activity_id, product_id, status, user_id, requested_at, processed_at, additional_data, created_at, updated_at) "
                + "VALUES " + values;
        LocalDateTime now = LocalDateTime.now();
        List<Object> args = new java.util.ArrayList<>(entries.size() * 9);
        for (CampaignActivityEntry entry : entries) {
            args.add(entry.getCampaignActivity().getId());
            args.add(entry.getProductId());
            args.add(entry.getStatus().name());
            args.add(entry.getUserId());
            args.add(entry.getRequestedAt());
            args.add(entry.getProcessedAt());
            args.add(entry.getInfo());
            args.add(now);
            args.add(now);
        }
        jdbcTemplate.update(sql, args.toArray());
    }

    private void insertPurchases(List<Purchase> purchases) {
        if (purchases.isEmpty()) {
            return;
        }

        String values = String.join(",", java.util.Collections.nCopies(purchases.size(), "(?,?,?,?,?,?,?,?,?,?)"));
        String sql = "INSERT INTO purchases "
                + "(user_id, product_id, campaign_activity_id, purchase_type, price, quantity, purchase_at, status, cancelled_at, cancellation_reason) "
                + "VALUES " + values;
        List<Object> args = new java.util.ArrayList<>(purchases.size() * 10);
        for (Purchase purchase : purchases) {
            args.add(purchase.getUserId());
            args.add(purchase.getProductId());
            args.add(purchase.getCampaignActivityId());
            args.add(purchase.getPurchaseType().name());
            args.add(purchase.getPrice());
            args.add(purchase.getQuantity());
            args.add(purchase.getPurchaseAt());
            args.add(PurchaseStatus.CONFIRMED.name());
            args.add(null);
            args.add(null);
        }
        jdbcTemplate.update(sql, args.toArray());
    }

    private Instant requestedAt(CampaignActivityKafkaProducerDto dto) {
        return Optional.ofNullable(dto.getTimestamp()).map(Instant::ofEpochMilli).orElseGet(Instant::now);
    }

    private int quantity(CampaignActivityKafkaProducerDto dto) {
        return dto.getQuantity() == null ? 1 : dto.getQuantity().intValue();
    }

    public record LedgerResult(
            CampaignActivityKafkaProducerDto message,
            Long campaignId,
            boolean purchaseCreated,
            Instant purchasedAt) {
    }
}
