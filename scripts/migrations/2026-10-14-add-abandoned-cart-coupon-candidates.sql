CREATE TABLE abandoned_cart_coupon_campaigns (
    id BIGINT NOT NULL AUTO_INCREMENT,
    marketing_action_id BIGINT NOT NULL,
    delay_minutes INT NOT NULL,
    is_active BIT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_abandoned_cart_coupon_campaign_action UNIQUE (marketing_action_id),
    CONSTRAINT fk_abandoned_cart_coupon_campaign_action
        FOREIGN KEY (marketing_action_id) REFERENCES marketing_actions (id)
);

CREATE TABLE abandoned_cart_coupon_candidates (
    id BIGINT NOT NULL AUTO_INCREMENT,
    campaign_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    last_cart_activity_at DATETIME NOT NULL,
    due_at DATETIME NOT NULL,
    status VARCHAR(20) NOT NULL,
    claimed_at DATETIME NULL,
    marketing_action_execution_id BIGINT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_abandoned_cart_coupon_candidate UNIQUE (campaign_id, user_id, product_id),
    CONSTRAINT fk_abandoned_cart_coupon_candidate_campaign
        FOREIGN KEY (campaign_id) REFERENCES abandoned_cart_coupon_campaigns (id),
    INDEX idx_abandoned_cart_candidate_campaign_due (campaign_id, status, due_at, id)
);
