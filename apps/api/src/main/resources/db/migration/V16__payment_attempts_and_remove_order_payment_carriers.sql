CREATE TABLE payment_attempts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    order_id uuid NOT NULL,
    channel_id uuid NOT NULL,
    channel_code_snapshot varchar(128) NOT NULL,
    provider_type varchar(128) NOT NULL,
    payment_type varchar(32) NOT NULL,
    provider_config_snapshot text NOT NULL,
    merchant_trade_no varchar(255) NOT NULL,
    provider_trade_no varchar(255),
    amount numeric(10,2) NOT NULL,
    status varchar(16) NOT NULL CHECK (status IN ('PENDING', 'PAID', 'FAILED', 'CLOSED')),
    next_action text NOT NULL,
    expires_at timestamp
);

CREATE UNIQUE INDEX uq_payment_attempts_merchant_trade_no
    ON payment_attempts(merchant_trade_no);
CREATE INDEX idx_payment_attempts_order_status_expires
    ON payment_attempts(order_id, status, expires_at, created_at DESC);
CREATE INDEX idx_payment_attempts_provider_trade_no
    ON payment_attempts(provider_trade_no);
CREATE INDEX idx_payment_attempts_channel
    ON payment_attempts(channel_id, channel_code_snapshot);

ALTER TABLE orders
    DROP COLUMN payment_url,
    DROP COLUMN qrcode_url,
    DROP COLUMN epay_trade_no;

ALTER TABLE webhook_events
    ADD COLUMN payment_attempt_id uuid;

CREATE INDEX idx_webhook_events_payment_attempt
    ON webhook_events(payment_attempt_id);
