-- A signed notification is only a hint to fetch the authoritative product detail.
CREATE TABLE supplier_goods_notifications (
    id uuid PRIMARY KEY,
    account_id uuid NOT NULL REFERENCES supplier_accounts(id),
    remote_goods_id varchar(120) NOT NULL,
    event_hash varchar(64) NOT NULL,
    status varchar(16) NOT NULL CHECK (status IN ('PENDING','SYNCED','IGNORED')),
    error_code varchar(64),
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_attempt_at timestamp,
    CONSTRAINT uq_supplier_goods_notification UNIQUE(account_id,event_hash)
);
CREATE INDEX idx_supplier_goods_notification_pending
    ON supplier_goods_notifications(status,last_attempt_at,created_at);
