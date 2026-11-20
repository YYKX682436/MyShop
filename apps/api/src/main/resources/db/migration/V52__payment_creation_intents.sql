ALTER TABLE payment_attempts
    ALTER COLUMN next_action DROP NOT NULL,
    ADD COLUMN creation_status varchar(16) NOT NULL DEFAULT 'READY',
    ADD COLUMN creation_started_at timestamp,
    ADD COLUMN creation_device varchar(64),
    ADD COLUMN product_name_snapshot text,
    ADD COLUMN client_ip_snapshot varchar(255),
    ADD COLUMN notify_url_snapshot text,
    ADD COLUMN return_url_snapshot text,
    ADD COLUMN create_response_trade_no varchar(255),
    ADD CONSTRAINT ck_payment_attempt_creation_status
        CHECK (creation_status IN ('INTENT', 'SUBMITTING', 'READY', 'REJECTED', 'UNKNOWN'));

-- Existing attempts already have a carrier. New unfinished creations are
-- serialized per order even if a caller omits the application row lock.
CREATE UNIQUE INDEX uq_payment_attempt_unresolved_creation
    ON payment_attempts(order_id)
    WHERE creation_status IN ('INTENT', 'SUBMITTING', 'UNKNOWN') AND status = 'PENDING';
