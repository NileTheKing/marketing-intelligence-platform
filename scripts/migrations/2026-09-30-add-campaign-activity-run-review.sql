-- Coupon campaign run snapshots and operator approval before the existing dispatch pipeline.

ALTER TABLE marketing_actions
    MODIFY COLUMN marketing_rule_id BIGINT NULL;

ALTER TABLE marketing_action_executions
    MODIFY COLUMN marketing_rule_id BIGINT NULL;

ALTER TABLE campaign_activities
    ADD COLUMN expected_recipient_count INT NULL,
    ADD COLUMN max_recipient_count INT NULL,
    ADD COLUMN purpose VARCHAR(50) NULL,
    ADD COLUMN operator_memo VARCHAR(2000) NULL,
    ADD COLUMN marketing_action_id BIGINT NULL,
    ADD CONSTRAINT uk_campaign_activity_marketing_action UNIQUE (marketing_action_id),
    ADD CONSTRAINT fk_campaign_activity_marketing_action
        FOREIGN KEY (marketing_action_id) REFERENCES marketing_actions (id);

CREATE TABLE campaign_activity_runs (
    id BIGINT NOT NULL AUTO_INCREMENT,
    campaign_activity_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    target_count INT NOT NULL,
    blocked_reason VARCHAR(1000) NULL,
    fact_snapshot_json JSON NULL,
    analysis_claim_token VARCHAR(80) NULL,
    analysis_claim_expires_at DATETIME NULL,
    analysis_summary VARCHAR(2000) NULL,
    recommendation VARCHAR(32) NULL,
    operator_feedback VARCHAR(1000) NULL,
    slack_message_ts VARCHAR(64) NULL,
    decided_by VARCHAR(128) NULL,
    decided_at DATETIME NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_campaign_activity_run_activity
        FOREIGN KEY (campaign_activity_id) REFERENCES campaign_activities (id),
    INDEX idx_campaign_activity_run_claim (status, analysis_claim_expires_at)
);

ALTER TABLE marketing_action_executions
    ADD COLUMN campaign_activity_run_id BIGINT NULL,
    ADD CONSTRAINT uk_marketing_execution_campaign_run_target
        UNIQUE (campaign_activity_run_id, user_id),
    ADD CONSTRAINT fk_marketing_execution_campaign_run
        FOREIGN KEY (campaign_activity_run_id) REFERENCES campaign_activity_runs (id);

CREATE TABLE campaign_activity_run_targets (
    id BIGINT NOT NULL AUTO_INCREMENT,
    run_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    execution_id BIGINT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_campaign_run_target UNIQUE (run_id, user_id),
    CONSTRAINT uk_campaign_run_target_execution UNIQUE (execution_id),
    CONSTRAINT fk_campaign_run_target_run
        FOREIGN KEY (run_id) REFERENCES campaign_activity_runs (id),
    CONSTRAINT fk_campaign_run_target_execution
        FOREIGN KEY (execution_id) REFERENCES marketing_action_executions (id),
    INDEX idx_campaign_run_target_execution (execution_id)
);
