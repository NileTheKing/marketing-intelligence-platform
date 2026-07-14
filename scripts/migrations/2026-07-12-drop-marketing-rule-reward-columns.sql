-- Clean-cutover migration: MarketingRule single-reward model removed in favor of MarketingAction.
-- Run once against the existing VM DB. Not idempotent by design (single manual cutover, no backfill/fallback).
ALTER TABLE marketing_rules DROP COLUMN reward_type, DROP COLUMN reward_reference_id;
