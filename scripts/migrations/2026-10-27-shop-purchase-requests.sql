CREATE TABLE shop_purchase_requests (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    request_id CHAR(36) NOT NULL,
    product_id BIGINT NOT NULL,
    user_coupon_id BIGINT NULL,
    quantity INT NOT NULL,
    purchase_id BIGINT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_shop_request_user_request UNIQUE (user_id, request_id),
    CONSTRAINT uk_shop_request_purchase UNIQUE (purchase_id)
) ENGINE=InnoDB;
