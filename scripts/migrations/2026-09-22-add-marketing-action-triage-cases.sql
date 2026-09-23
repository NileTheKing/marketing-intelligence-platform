-- Core-owned DLQ triage cases. Run after the dispatch-history split migration.
-- The AI service must not receive credentials for this schema/table.

CREATE TABLE marketing_action_triage_cases (
    id BIGINT NOT NULL AUTO_INCREMENT,
    dispatch_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    failure_category VARCHAR(32) NOT NULL,
    analysis_claim_token VARCHAR(80) NULL,
    analysis_claim_expires_at DATETIME NULL,
    analysis_attempt_count INT NOT NULL DEFAULT 0,
    fact_snapshot_json JSON NULL,
    recommendation VARCHAR(32) NULL,
    confidence DOUBLE NULL,
    analysis_summary VARCHAR(2000) NULL,
    operator_guidance VARCHAR(1000) NULL,
    operator_next_step VARCHAR(1000) NULL,
    evidence_json JSON NULL,
    llm_model VARCHAR(100) NULL,
    failure_reason VARCHAR(1000) NULL,
    slack_message_ts VARCHAR(64) NULL,
    decided_by VARCHAR(128) NULL,
    decided_at DATETIME NULL,
    rejected_reason VARCHAR(1000) NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_marketing_triage_dispatch UNIQUE (dispatch_id),
    CONSTRAINT fk_marketing_triage_dispatch
        FOREIGN KEY (dispatch_id) REFERENCES marketing_action_dispatches (id),
    INDEX idx_marketing_triage_claim (status, analysis_claim_expires_at),
    INDEX idx_marketing_triage_created (created_at)
);
