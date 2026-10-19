package com.axon.core_service.commandprocessing;

import com.axon.core_service.service.AbandonedCartCouponCandidateService;
import com.axon.messaging.dto.UserBehaviorEventMessage;
import com.axon.messaging.topic.KafkaTopics;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AbandonedCartBehaviorConsumer {

    private static final String CART_ITEM_ADDED = "CART_ITEM_ADDED";
    private static final String CART_ITEM_REMOVED = "CART_ITEM_REMOVED";
    private static final String PURCHASE = "PURCHASE";

    private final AbandonedCartCouponCandidateService candidateService;

    @KafkaListener(topics = KafkaTopics.BEHAVIOR_EVENT, groupId = "axon-abandoned-cart-candidate-group")
    public void consume(List<UserBehaviorEventMessage> events) {
        for (UserBehaviorEventMessage event : events) {
            Long productId = productId(event);
            if (event.getUserId() == null || productId == null) {
                continue;
            }
            if (CART_ITEM_ADDED.equals(event.getTriggerType())) {
                candidateService.scheduleForCartItem(event.getUserId(), productId, event.getOccurredAt());
            } else if (CART_ITEM_REMOVED.equals(event.getTriggerType()) || PURCHASE.equals(event.getTriggerType())) {
                candidateService.cancelScheduledForPurchaseOrRemoval(event.getUserId(), productId);
            }
        }
    }

    private Long productId(UserBehaviorEventMessage event) {
        if (event.getProperties() == null) {
            return null;
        }
        Object value = event.getProperties().get("productId");
        return value instanceof Number number ? number.longValue() : null;
    }
}
