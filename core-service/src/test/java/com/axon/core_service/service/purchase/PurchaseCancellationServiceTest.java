package com.axon.core_service.service.purchase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.axon.core_service.domain.purchase.Purchase;
import com.axon.core_service.domain.purchase.PurchaseType;
import com.axon.core_service.repository.PurchaseRepository;
import com.axon.core_service.service.UserSummaryService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PurchaseCancellationServiceTest {

    @Test
    void cancellationUpdatesTheDurablePurchaseAndRebuildsItsUserSummary() {
        PurchaseRepository purchaseRepository = mock(PurchaseRepository.class);
        UserSummaryService userSummaryService = mock(UserSummaryService.class);
        PurchaseCancellationService service = new PurchaseCancellationService(purchaseRepository, userSummaryService);
        Purchase purchase = new Purchase(10L, 20L, 30L, PurchaseType.CAMPAIGNACTIVITY,
                BigDecimal.valueOf(10_000), 1, Instant.now());
        when(purchaseRepository.findById(1L)).thenReturn(Optional.of(purchase));

        assertThat(service.cancelConfirmedPurchase(1L, "customer request", Instant.now())).isTrue();

        verify(userSummaryService).rebuildPurchaseSummary(10L);
        assertThat(service.cancelConfirmedPurchase(1L, "duplicate", Instant.now())).isFalse();
    }
}
