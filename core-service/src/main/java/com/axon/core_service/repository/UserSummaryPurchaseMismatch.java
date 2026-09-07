package com.axon.core_service.repository;

import java.time.LocalDateTime;

/**
 * A database-level comparison between the purchase ledger and its summary projection.
 */
public interface UserSummaryPurchaseMismatch {

    Long getUserId();

    LocalDateTime getExpectedLastPurchaseAt();

    LocalDateTime getObservedLastPurchaseAt();
}
