CREATE TABLE audience_segments (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL,
    target_rfm_segment VARCHAR(20) NOT NULL,
    is_active BIT NOT NULL,
    created_at DATETIME(6),
    updated_at DATETIME(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_audience_segments_name UNIQUE (name)
);

ALTER TABLE marketing_rules
    ADD COLUMN audience_segment_id BIGINT NULL,
    ADD CONSTRAINT fk_marketing_rules_audience_segment
        FOREIGN KEY (audience_segment_id) REFERENCES audience_segments (id);
