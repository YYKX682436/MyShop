-- Opaque browser credentials are never stored. Only SHA-256 token digests are
-- persisted so sessions survive API restarts and can be revoked independently.
CREATE TABLE auth_sessions (
    token_hash char(64) PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    user_role varchar(20) NOT NULL CHECK (user_role IN ('USER', 'ADMIN', 'SUPER_ADMIN')),
    auth_version integer NOT NULL,
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    renewed_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at timestamp NOT NULL,
    revoked_at timestamp,
    revoked_reason varchar(32),
    CONSTRAINT chk_auth_sessions_expiry CHECK (expires_at > created_at),
    CONSTRAINT chk_auth_sessions_revocation CHECK (
        (revoked_at IS NULL AND revoked_reason IS NULL)
        OR (revoked_at IS NOT NULL AND revoked_reason IS NOT NULL)
    )
);
CREATE INDEX idx_auth_sessions_expiry ON auth_sessions(expires_at) WHERE revoked_at IS NULL;
CREATE INDEX idx_auth_sessions_user_active ON auth_sessions(user_id, auth_version) WHERE revoked_at IS NULL;

-- The attention endpoint only asks existence/count questions for these states.
CREATE INDEX idx_orders_paid_attention ON orders(paid_at, id) WHERE status = 'PAID';
CREATE INDEX idx_order_items_manual_attention ON order_items(order_id, fulfillment_status)
    WHERE delivery_type = 'MANUAL' AND fulfillment_status = 'WAITING_MANUAL';
CREATE INDEX idx_order_items_auto_attention ON order_items(order_id, fulfillment_status)
    WHERE delivery_type = 'AUTO' AND stock_mode = 'CARD_KEY' AND fulfillment_status = 'PENDING';
CREATE INDEX idx_webhook_events_payment_attention ON webhook_events(order_id, payment_attempt_id, created_at DESC)
    WHERE left(process_result, 10) = 'LATE_PAID_';
CREATE INDEX idx_manual_stocks_product_attention ON manual_stocks(product_id) INCLUDE (available_quantity);
CREATE INDEX idx_supplier_purchases_attention ON supplier_purchases(status, submit_started_at, order_id)
    WHERE status IN ('BLOCKED', 'UNKNOWN', 'SUBMITTING');
CREATE INDEX idx_supplier_aftercare_attention ON supplier_aftercare_operations(purchase_id, status, submitted_at)
    WHERE status IN ('INTENT', 'SUBMITTING', 'UNKNOWN', 'ACCEPTED');
CREATE INDEX idx_supplier_mapping_attention ON supplier_product_mappings(product_id, account_id, last_synced_at)
    WHERE sync_error IS NOT NULL;

INSERT INTO site_configs(config_key, config_value, config_group)
VALUES
    ('login_duration_days', '30', 'security'),
    ('low_stock_attention_enabled', 'false', 'inventory')
ON CONFLICT (config_key) DO NOTHING;
