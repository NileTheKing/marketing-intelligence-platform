-- Durable delivery history for behavior-triggered coupon and webhook actions.
-- Run once against an existing VM database. ddl-auto=update can create the table in development,
-- but this migration is the explicit production/manual schema cutover.
-- No UNIQUE(action_id,user_id,product_id): Redis dedup is TTL-scoped and later re-triggers are valid.
CREATE TABLE marketing_action_executions (
    id BIGINT NOT NULL AUTO_INCREMENT,
    marketing_action_id BIGINT NOT NULL,
    marketing_rule_id BIGINT NOT NULL,
    action_reference_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    product_id BIGINT NULL,
    channel VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    dispatch_version BIGINT NOT NULL DEFAULT 1,
    last_failure_reason VARCHAR(1000) NULL,
    dispatched_at DATETIME NULL,
    completed_at DATETIME NULL,
    dlt_at DATETIME NULL,
    created_at DATETIME NULL,
    updated_at DATETIME NULL,
    PRIMARY KEY (id),
    INDEX idx_marketing_execution_status_dlt (status, dlt_at),
    INDEX idx_marketing_execution_action_target (marketing_action_id, user_id, product_id)
);
