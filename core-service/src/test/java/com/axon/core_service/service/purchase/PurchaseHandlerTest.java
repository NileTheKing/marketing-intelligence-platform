package com.axon.core_service.service.purchase;

import static org.mockito.Mockito.verify;

import com.axon.core_service.domain.dto.purchase.PurchaseInfoDto;
import com.axon.core_service.domain.purchase.PurchaseType;
import com.axon.core_service.service.ProductService;
import com.axon.core_service.service.UserSummaryService;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PurchaseHandlerTest {

    @Mock private ProductService productService;
    @Mock private UserSummaryService userSummaryService;
    @Mock private PurchaseService purchaseService;
    @InjectMocks private PurchaseHandler purchaseHandler;

    @Test
    void handleShopPurchaseImmediately() {
        PurchaseInfoDto purchase = new PurchaseInfoDto(1L, 1L, 1L, 1L, Instant.now(), PurchaseType.SHOP,
                BigDecimal.TEN, 1, Instant.now());

        purchaseHandler.handle(purchase);

        verify(productService).decreaseStock(purchase.productId(), purchase.quantity());
        verify(userSummaryService).recordPurchase(purchase.userId(), purchase.occurredAt());
        verify(purchaseService).createPurchase(purchase);
    }
}
