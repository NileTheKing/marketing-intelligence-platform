-- Existing databases with the old (status, due_at, id) index only.
-- Skip this script on databases created with the updated 2026-10-14 script.
ALTER TABLE abandoned_cart_coupon_candidates
    ADD INDEX idx_abandoned_cart_candidate_campaign_due (campaign_id, status, due_at, id),
    DROP INDEX idx_abandoned_cart_candidate_due;
