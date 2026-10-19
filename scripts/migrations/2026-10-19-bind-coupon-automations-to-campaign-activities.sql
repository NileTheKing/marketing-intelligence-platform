-- Requires the campaign/activity, marketing-action, abandoned-cart, and dormant-customer
-- feature migrations. This migration is additive and does not delete legacy settings.
-- Run during a maintenance window with writes disabled for campaigns, campaign activities,
-- marketing actions, and both automation tables; synthetic IDs are allocated from MAX(id).
ALTER TABLE campaign_activities
    ADD COLUMN coupon_delivery_mode VARCHAR(30) NOT NULL DEFAULT 'DIRECT';

ALTER TABLE abandoned_cart_coupon_campaigns
    ADD COLUMN campaign_activity_id BIGINT NULL,
    ADD CONSTRAINT uk_abandoned_cart_coupon_campaign_activity UNIQUE (campaign_activity_id),
    ADD CONSTRAINT fk_abandoned_cart_coupon_campaign_activity
        FOREIGN KEY (campaign_activity_id) REFERENCES campaign_activities (id);

ALTER TABLE dormant_customer_campaigns
    ADD COLUMN campaign_activity_id BIGINT NULL,
    ADD CONSTRAINT uk_dormant_customer_campaign_activity UNIQUE (campaign_activity_id),
    ADD CONSTRAINT fk_dormant_customer_campaign_activity
        FOREIGN KEY (campaign_activity_id) REFERENCES campaign_activities (id);

-- Legacy settings are linked only when their MarketingAction is unambiguous and is
-- not already owned by an activity or by the other automation table. Ambiguous rows
-- remain unlinked (and therefore non-executable) for explicit operator mapping.
CREATE TEMPORARY TABLE tmp_coupon_automation_activity_map (
    mode VARCHAR(30) NOT NULL,
    config_id BIGINT NOT NULL,
    campaign_id BIGINT NOT NULL,
    activity_id BIGINT NOT NULL,
    action_id BIGINT NOT NULL,
    coupon_id BIGINT NULL,
    PRIMARY KEY (mode, config_id)
);

SET @campaign_id_base = (SELECT COALESCE(MAX(id), 0) FROM campaigns)
    + GREATEST((SELECT COALESCE(MAX(id), 0) FROM abandoned_cart_coupon_campaigns),
               (SELECT COALESCE(MAX(id), 0) FROM dormant_customer_campaigns)) + 1;
SET @activity_id_base = (SELECT COALESCE(MAX(id), 0) FROM campaign_activities)
    + GREATEST((SELECT COALESCE(MAX(id), 0) FROM abandoned_cart_coupon_campaigns),
               (SELECT COALESCE(MAX(id), 0) FROM dormant_customer_campaigns)) + 1;

SET @config_id_offset = (SELECT COALESCE(MAX(id), 0) FROM abandoned_cart_coupon_campaigns);

INSERT INTO tmp_coupon_automation_activity_map (mode, config_id, campaign_id, activity_id, action_id, coupon_id)
SELECT 'ABANDONED_CART', config.id, @campaign_id_base + config.id, @activity_id_base + config.id,
       action.id, action.reference_id
FROM abandoned_cart_coupon_campaigns config
JOIN marketing_actions action ON action.id = config.marketing_action_id
WHERE config.campaign_activity_id IS NULL AND action.action_type = 'COUPON'
  AND action.marketing_rule_id IS NULL
  AND EXISTS (SELECT 1 FROM coupons coupon WHERE coupon.id = action.reference_id)
  AND NOT EXISTS (SELECT 1 FROM campaign_activities activity WHERE activity.marketing_action_id = action.id)
  AND NOT EXISTS (SELECT 1 FROM dormant_customer_campaigns other
                  WHERE other.marketing_action_id = action.id);

INSERT INTO tmp_coupon_automation_activity_map (mode, config_id, campaign_id, activity_id, action_id, coupon_id)
SELECT 'DORMANT_CUSTOMER', config.id,
       @campaign_id_base + @config_id_offset + config.id,
       @activity_id_base + @config_id_offset + config.id,
       action.id, action.reference_id
FROM dormant_customer_campaigns config
JOIN marketing_actions action ON action.id = config.marketing_action_id
WHERE config.campaign_activity_id IS NULL AND action.action_type = 'COUPON'
  AND action.marketing_rule_id IS NULL
  AND EXISTS (SELECT 1 FROM coupons coupon WHERE coupon.id = action.reference_id)
  AND NOT EXISTS (SELECT 1 FROM campaign_activities activity WHERE activity.marketing_action_id = action.id)
  AND NOT EXISTS (SELECT 1 FROM abandoned_cart_coupon_campaigns other
                  WHERE other.marketing_action_id = action.id);

INSERT INTO campaigns (id, name, created_at, updated_at)
SELECT campaign_id, CONCAT('Legacy coupon automation ', mode, ' ', config_id), CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM tmp_coupon_automation_activity_map;

-- Historical automations are PAUSED. Operators must explicitly activate the newly
-- linked activity after reviewing its synthetic campaign and broad legacy window.
INSERT INTO campaign_activities
    (id, campaign_id, marketing_action_id, coupon_id, name, status, start_date, end_date,
     activity_type, price, quantity, synced_count, coupon_delivery_mode, created_at, updated_at)
SELECT activity_id, campaign_id, action_id, coupon_id,
       CONCAT('Legacy ', mode, ' coupon automation ', config_id), 'PAUSED',
       '2000-01-01 00:00:00', '9999-12-31 23:59:59', 'COUPON', 0, 0, 0, mode,
       CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM tmp_coupon_automation_activity_map;

UPDATE abandoned_cart_coupon_campaigns config
JOIN tmp_coupon_automation_activity_map mapping ON mapping.config_id = config.id
SET config.campaign_activity_id = mapping.activity_id
WHERE mapping.mode = 'ABANDONED_CART';

UPDATE dormant_customer_campaigns config
JOIN tmp_coupon_automation_activity_map mapping ON mapping.config_id = config.id
SET config.campaign_activity_id = mapping.activity_id
WHERE mapping.mode = 'DORMANT_CUSTOMER';

-- Activity status is now the only activation source. Disable every legacy row,
-- including ambiguous/unmapped rows, while preserving IDs and configuration values.
UPDATE abandoned_cart_coupon_campaigns SET is_active = b'0';
UPDATE dormant_customer_campaigns SET is_active = b'0';

DROP TEMPORARY TABLE tmp_coupon_automation_activity_map;

-- Review unresolved rows before manually mapping and activating them.
SELECT 'abandoned_cart' AS config_type, config.id, config.marketing_action_id,
       CASE
           WHEN action.id IS NULL THEN 'MISSING_MARKETING_ACTION'
           WHEN action.action_type <> 'COUPON' THEN 'NOT_COUPON_ACTION'
           WHEN action.reference_id IS NULL OR coupon.id IS NULL THEN 'MISSING_COUPON'
           WHEN action.marketing_rule_id IS NOT NULL THEN 'RULE_OWNED_ACTION'
           WHEN EXISTS (SELECT 1 FROM campaign_activities activity
                        WHERE activity.marketing_action_id = action.id) THEN 'ACTION_ALREADY_LINKED_TO_ACTIVITY'
           WHEN EXISTS (SELECT 1 FROM dormant_customer_campaigns other
                        WHERE other.marketing_action_id = action.id) THEN 'ACTION_SHARED_WITH_DORMANT_CONFIG'
           ELSE 'REVIEW_MAPPING'
       END AS mapping_issue
FROM abandoned_cart_coupon_campaigns config
LEFT JOIN marketing_actions action ON action.id = config.marketing_action_id
LEFT JOIN coupons coupon ON coupon.id = action.reference_id
WHERE config.campaign_activity_id IS NULL
UNION ALL
SELECT 'dormant_customer', config.id, config.marketing_action_id,
       CASE
           WHEN action.id IS NULL THEN 'MISSING_MARKETING_ACTION'
           WHEN action.action_type <> 'COUPON' THEN 'NOT_COUPON_ACTION'
           WHEN action.reference_id IS NULL OR coupon.id IS NULL THEN 'MISSING_COUPON'
           WHEN action.marketing_rule_id IS NOT NULL THEN 'RULE_OWNED_ACTION'
           WHEN EXISTS (SELECT 1 FROM campaign_activities activity
                        WHERE activity.marketing_action_id = action.id) THEN 'ACTION_ALREADY_LINKED_TO_ACTIVITY'
           WHEN EXISTS (SELECT 1 FROM abandoned_cart_coupon_campaigns other
                        WHERE other.marketing_action_id = action.id) THEN 'ACTION_SHARED_WITH_ABANDONED_CART_CONFIG'
           ELSE 'REVIEW_MAPPING'
       END AS mapping_issue
FROM dormant_customer_campaigns config
LEFT JOIN marketing_actions action ON action.id = config.marketing_action_id
LEFT JOIN coupons coupon ON coupon.id = action.reference_id
WHERE config.campaign_activity_id IS NULL;
