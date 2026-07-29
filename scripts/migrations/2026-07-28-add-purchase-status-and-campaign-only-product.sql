-- Purchase cancellation and campaign-only product V1.
-- Run once on an existing MySQL deployment before enabling the application code.

ALTER TABLE products
    ADD COLUMN campaign_only BOOLEAN NOT NULL DEFAULT FALSE;

-- Mark the product selected for an FCFS activity before activating it:
-- UPDATE products SET campaign_only = TRUE WHERE id = <campaign_product_id>;

ALTER TABLE purchases
    ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'CONFIRMED',
    ADD COLUMN cancelled_at DATETIME NULL,
    ADD COLUMN cancellation_reason VARCHAR(500) NULL;

CREATE INDEX idx_purchase_activity_status_period
    ON purchases (campaign_activity_id, status, purchase_at);
