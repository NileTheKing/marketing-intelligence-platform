-- Apply after 2026-09-18-add-marketing-action-executions.sql when that table
-- was already created from the earlier V1 migration.
ALTER TABLE marketing_action_executions
    ADD COLUMN dispatch_version BIGINT NOT NULL DEFAULT 1;
