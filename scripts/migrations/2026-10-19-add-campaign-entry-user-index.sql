-- Manual migration; do not run against production as part of local validation.
-- Adds the customer-history lookup path while preserving the activity/user business unique key.
CREATE INDEX idx_campaign_entry_user_id ON campaign_activity_entries (user_id);
