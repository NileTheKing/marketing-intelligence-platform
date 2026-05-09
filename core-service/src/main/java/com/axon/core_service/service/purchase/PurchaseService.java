package com.axon.core_service.service.purchase;

import com.axon.core_service.domain.purchase.Purchase;
import com.axon.core_service.domain.dto.purchase.PurchaseInfoDto;
import com.axon.core_service.repository.PurchaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PurchaseService {
    private final PurchaseRepository purchaseRepository;

    @Transactional
    public void createPurchase(PurchaseInfoDto info) {
        log.info("Creating purchase record for userId={}, productId={}", info.userId(), info.productId());

        Purchase purchase = new Purchase(
                info.userId(),
                info.productId(),
                info.campaignActivityId(),
                info.purchaseType(),
                info.price(),
                info.quantity(),
                info.purchasedAt()
        );

        try {
            purchaseRepository.save(purchase);
            log.info("Saved purchase record for userId={}, productId={}", info.userId(), info.productId());
        } catch (Exception e) {
            log.warn("Failed to save purchase: activity={}, user={}, error={}",
                    info.campaignActivityId(), info.userId(), e.getMessage());
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void createPurchaseBatch(List<PurchaseInfoDto> purchases) {
        if (purchases.isEmpty()) {
            return;
        }

        log.info("Creating {} purchase records", purchases.size());

        List<Purchase> purchaseEntities = purchases.stream()
                .map(info -> new Purchase(
                        info.userId(),
                        info.productId(),
                        info.campaignActivityId(),
                        info.purchaseType(),
                        info.price(),
                        info.quantity(),
                        info.purchasedAt()
                ))
                .toList();

        try {
            purchaseRepository.saveAll(purchaseEntities);
            log.info("[Purchase] Saved {} purchase records successfully", purchaseEntities.size());
        } catch (Exception e) {
            log.warn("[Purchase] Batch failed, retrying individually. Error: {}", e.getMessage());
            int savedCount = 0;
            for (Purchase purchase : purchaseEntities) {
                try {
                    saveSinglePurchaseInNewTransaction(purchase);
                    savedCount++;
                } catch (Exception ex) {
                    log.debug("Skipping failed purchase: userId={}", purchase.getUserId());
                }
            }
            log.info("[Purchase] Recovered {}/{} purchases via individual retry", savedCount, purchaseEntities.size());
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveSinglePurchaseInNewTransaction(Purchase purchase) {
        purchaseRepository.save(purchase);
    }
}
