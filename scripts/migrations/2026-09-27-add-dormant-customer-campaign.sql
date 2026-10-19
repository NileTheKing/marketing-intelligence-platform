CREATE TABLE dormant_customer_campaigns (
    id BIGINT NOT NULL AUTO_INCREMENT,
    marketing_action_id BIGINT NOT NULL,
    dormant_days INT NOT NULL,
    is_active BIT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_dormant_campaign_action UNIQUE (marketing_action_id),
    CONSTRAINT fk_dormant_campaign_action
        FOREIGN KEY (marketing_action_id) REFERENCES marketing_actions (id)
);

ALTER TABLE user_summary
    ADD INDEX idx_user_summary_last_purchase_user (last_purchase_at, user_id);

ALTER TABLE marketing_action_executions
    MODIFY COLUMN marketing_rule_id BIGINT NULL,
    ADD COLUMN reengagement_campaign_id BIGINT NULL,
    ADD CONSTRAINT uk_marketing_execution_reengagement_target
        UNIQUE (reengagement_campaign_id, user_id);

ALTER TABLE marketing_actions
    MODIFY COLUMN marketing_rule_id BIGINT NULL;
