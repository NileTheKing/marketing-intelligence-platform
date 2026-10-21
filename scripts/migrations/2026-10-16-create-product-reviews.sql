CREATE TABLE product_reviews (
    id BIGINT NOT NULL AUTO_INCREMENT,
    product_id BIGINT NOT NULL,
    purchase_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    rating INT NOT NULL,
    content VARCHAR(1000) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_product_review_purchase UNIQUE (purchase_id),
    CONSTRAINT fk_product_review_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT fk_product_review_purchase FOREIGN KEY (purchase_id) REFERENCES purchases (id),
    INDEX idx_product_review_product_created (product_id, created_at)
);
