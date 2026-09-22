-- Split the stable marketing action obligation from each Kafka delivery generation.
-- Run once after the 2026-09-18 marketing action execution migrations.
-- The backfill preserves the existing execution id and copies its current lifecycle
-- into SYSTEM dispatch sequence 1 before the old lifecycle columns are removed.

CREATE TABLE marketing_action_dispatches (
    id BIGINT NOT NULL AUTO_INCREMENT,
    execution_id BIGINT NOT NULL,
    dispatch_sequence BIGINT NOT NULL,
    initiated_by VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    last_failure_reason VARCHAR(1000) NULL,
    dispatched_at DATETIME NULL,
    completed_at DATETIME NULL,
    dlt_at DATETIME NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_marketing_dispatch_execution_sequence UNIQUE (execution_id, dispatch_sequence),
    CONSTRAINT fk_marketing_dispatch_execution
        FOREIGN KEY (execution_id) REFERENCES marketing_action_executions (id),
    INDEX idx_marketing_dispatch_status_dlt (status, dlt_at),
    INDEX idx_marketing_dispatch_execution_sequence (execution_id, dispatch_sequence)
);

INSERT INTO marketing_action_dispatches (
    execution_id,
    dispatch_sequence,
    initiated_by,
    status,
    attempt_count,
    last_failure_reason,
    dispatched_at,
    completed_at,
    dlt_at,
    created_at,
    updated_at
)
SELECT id,
       1,
       'SYSTEM',
       status,
       attempt_count,
       last_failure_reason,
       dispatched_at,
       completed_at,
       dlt_at,
       COALESCE(created_at, CURRENT_TIMESTAMP),
       COALESCE(updated_at, CURRENT_TIMESTAMP)
FROM marketing_action_executions;

ALTER TABLE marketing_action_executions
    DROP INDEX idx_marketing_execution_status_dlt,
    DROP COLUMN status,
    DROP COLUMN attempt_count,
    DROP COLUMN dispatch_version,
    DROP COLUMN last_failure_reason,
    DROP COLUMN dispatched_at,
    DROP COLUMN completed_at,
    DROP COLUMN dlt_at;
