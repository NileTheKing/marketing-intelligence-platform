CREATE TABLE payment_reservations (
    reservation_id CHAR(36) NOT NULL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    activity_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    deadline TIMESTAMP(6) NOT NULL,
    unit_price DECIMAL(19,2) NOT NULL,
    total_amount DECIMAL(19,2) NOT NULL,
    currency CHAR(3) NOT NULL,
    state VARCHAR(32) NOT NULL,
    release_pending BOOLEAN NOT NULL DEFAULT FALSE,
    INDEX idx_reservation_expiry (state, deadline),
    INDEX idx_reservation_release (release_pending)
);

CREATE TABLE payments (
    payment_id CHAR(36) NOT NULL PRIMARY KEY,
    reservation_id CHAR(36) NOT NULL,
    state VARCHAR(32) NOT NULL,
    owner_token CHAR(36) NOT NULL,
    claimed_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    approval_id VARCHAR(255),
    UNIQUE KEY uk_payment_reservation (reservation_id),
    CONSTRAINT fk_payment_reservation FOREIGN KEY (reservation_id) REFERENCES payment_reservations(reservation_id)
);

CREATE TABLE payment_outbox (
    event_id CHAR(36) NOT NULL PRIMARY KEY,
    payment_id CHAR(36) NOT NULL,
    payload TEXT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    state VARCHAR(32) NOT NULL,
    owner_token CHAR(36),
    claimed_until TIMESTAMP(6),
    attempts INT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_payment_completed_event (payment_id),
    INDEX idx_payment_outbox_claim (state, claimed_until),
    CONSTRAINT fk_outbox_payment FOREIGN KEY (payment_id) REFERENCES payments(payment_id)
);
