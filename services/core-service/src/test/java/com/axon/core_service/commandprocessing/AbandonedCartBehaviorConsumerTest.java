package com.axon.core_service.commandprocessing;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.axon.core_service.service.AbandonedCartCouponCandidateService;
import com.axon.messaging.dto.UserBehaviorEventMessage;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AbandonedCartBehaviorConsumerTest {

    @Mock private AbandonedCartCouponCandidateService candidateService;

    @Test
    void schedulesCandidateOnlyForCartItemAddedWithUserAndProduct() {
        Instant occurredAt = Instant.parse("2026-10-14T00:00:00Z");
        event("CART_ITEM_ADDED", 7L, 10L, occurredAt);

        verify(candidateService).scheduleForCartItem(7L, 10L, occurredAt);
    }

    @Test
    void purchaseAndRemovalCancelCandidate() {
        AbandonedCartBehaviorConsumer consumer = new AbandonedCartBehaviorConsumer(candidateService);
        consumer.consume(List.of(
                message("PURCHASE", 7L, 10L, Instant.now()),
                message("CART_ITEM_REMOVED", 8L, 11L, Instant.now())));

        verify(candidateService).cancelScheduledForPurchaseOrRemoval(7L, 10L);
        verify(candidateService).cancelScheduledForPurchaseOrRemoval(8L, 11L);
    }

    @Test
    void ignoresEventsWithoutAProduct() {
        AbandonedCartBehaviorConsumer consumer = new AbandonedCartBehaviorConsumer(candidateService);
        consumer.consume(List.of(UserBehaviorEventMessage.builder()
                .triggerType("CART_ITEM_ADDED")
                .userId(7L)
                .occurredAt(Instant.now())
                .build()));

        verifyNoInteractions(candidateService);
    }

    private void event(String triggerType, Long userId, Long productId, Instant occurredAt) {
        AbandonedCartBehaviorConsumer consumer = new AbandonedCartBehaviorConsumer(candidateService);
        consumer.consume(List.of(message(triggerType, userId, productId, occurredAt)));
    }

    private UserBehaviorEventMessage message(String triggerType, Long userId, Long productId, Instant occurredAt) {
        return UserBehaviorEventMessage.builder()
                .triggerType(triggerType)
                .userId(userId)
                .occurredAt(occurredAt)
                .properties(Map.of("productId", productId))
                .build();
    }
}
