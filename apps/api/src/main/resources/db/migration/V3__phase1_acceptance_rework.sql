ALTER TABLE orders
    ADD COLUMN guest_lookup_token varchar(128),
    ADD COLUMN idempotency_fingerprint varchar(64);

COMMENT ON COLUMN orders.guest_lookup_token IS
    'Sensitive recoverable guest order credential. Never expose in admin lists or logs; protect database backups.';

COMMENT ON COLUMN orders.idempotency_fingerprint IS
    'Client checkout payload fingerprint used to reject replay with changed intent.';

CREATE UNIQUE INDEX uq_payment_channels_active_code
    ON payment_channels(channel_code)
    WHERE is_deleted = 0;

CREATE INDEX idx_orders_paid_fulfillment
    ON orders(status, delivered_at, created_at)
    WHERE status = 'PAID';
