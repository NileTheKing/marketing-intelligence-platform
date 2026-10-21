-- Treatment/holdout assignment and post-dispatch campaign performance analysis.

ALTER TABLE campaign_activities
    ADD COLUMN holdout_rate_percent INT NULL;

ALTER TABLE campaign_activity_runs
    ADD COLUMN treatment_count INT NOT NULL DEFAULT 0,
    ADD COLUMN holdout_count INT NOT NULL DEFAULT 0,
    ADD COLUMN dispatched_at DATETIME NULL;

-- Existing runs had no holdout group and already reserved every saved target.
UPDATE campaign_activity_runs
SET treatment_count = target_count
WHERE treatment_count = 0;

ALTER TABLE campaign_activity_run_targets
    ADD COLUMN assignment_group VARCHAR(16) NOT NULL DEFAULT 'TREATMENT',
    ADD COLUMN baseline_rfm_segment VARCHAR(16) NULL,
    ADD INDEX idx_campaign_run_target_analysis
        (run_id, assignment_group, baseline_rfm_segment, user_id);

ALTER TABLE user_coupons
    ADD COLUMN marketing_action_execution_id BIGINT NULL,
    ADD UNIQUE KEY uk_user_coupon_execution (marketing_action_execution_id);

ALTER TABLE purchases
    ADD COLUMN user_coupon_id BIGINT NULL,
    ADD UNIQUE KEY uk_purchase_user_coupon (user_coupon_id);
