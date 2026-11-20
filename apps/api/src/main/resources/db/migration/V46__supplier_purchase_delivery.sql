-- Supplier purchases are separate from customer orders and local inventory.
ALTER TABLE order_items DROP CONSTRAINT order_items_stock_mode_check;
ALTER TABLE order_items ADD CONSTRAINT order_items_stock_mode_check
    CHECK (stock_mode IN ('CARD_KEY', 'MANUAL', 'UNLIMITED', 'SUPPLIER'));

CREATE TABLE supplier_purchases (
    id uuid PRIMARY KEY,
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    order_id uuid NOT NULL REFERENCES orders(id),
    order_item_id uuid NOT NULL UNIQUE REFERENCES order_items(id),
    account_id uuid NOT NULL REFERENCES supplier_accounts(id),
    remote_goods_id varchar(120) NOT NULL,
    remote_sku varchar(255) NOT NULL,
    goods_type integer NOT NULL CHECK (goods_type IN (1,3)),
    outer_number varchar(100) NOT NULL UNIQUE,
    upstream_order_number varchar(120),
    buyer_quantity integer NOT NULL CHECK (buyer_quantity > 0),
    multiple integer NOT NULL CHECK (multiple > 0),
    actual_quantity integer NOT NULL CHECK (actual_quantity > 0),
    unit_cost numeric(24,8) NOT NULL CHECK (unit_cost >= 0),
    safe_price numeric(24,8) NOT NULL CHECK (safe_price >= 0),
    attach jsonb NOT NULL DEFAULT '[]'::jsonb,
    recharge_template jsonb NOT NULL DEFAULT '[]'::jsonb,
    status varchar(32) NOT NULL,
    upstream_status integer,
    actual_cost numeric(24,8),
    refunded_amount numeric(24,8),
    result text,
    start_count text,
    now_count text,
    end_count text,
    last_checked_at timestamp,
    submit_started_at timestamp,
    error_code varchar(64),
    CONSTRAINT supplier_purchase_status_check CHECK (status IN
        ('WAITING_PAYMENT','SUBMITTING','UNKNOWN','UNPAID','PROCESSING','BLOCKED','COMPLETED','WITHDRAWN','REFUNDING','REFUNDED'))
);
CREATE INDEX idx_supplier_purchases_recovery ON supplier_purchases(status, last_checked_at, created_at);
CREATE UNIQUE INDEX uq_supplier_order_number ON supplier_purchases(account_id,upstream_order_number) WHERE upstream_order_number IS NOT NULL;

CREATE TABLE supplier_delivery_cards (
    id uuid PRIMARY KEY,
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    purchase_id uuid NOT NULL REFERENCES supplier_purchases(id),
    account_id uuid NOT NULL REFERENCES supplier_accounts(id),
    remote_card_id varchar(120) NOT NULL,
    card_no text,
    card_pwd text,
    card_status integer NOT NULL,
    cancel_request integer NOT NULL,
    CONSTRAINT uq_supplier_card UNIQUE(account_id, remote_card_id)
);
CREATE INDEX idx_supplier_cards_purchase ON supplier_delivery_cards(purchase_id);

CREATE TABLE supplier_callback_events (
    id uuid PRIMARY KEY,
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    account_id uuid NOT NULL REFERENCES supplier_accounts(id),
    purchase_id uuid NOT NULL REFERENCES supplier_purchases(id),
    event_hash varchar(64) NOT NULL,
    upstream_status integer NOT NULL,
    encrypted_cards text,
    processed boolean NOT NULL DEFAULT false,
    CONSTRAINT uq_supplier_callback_event UNIQUE(account_id,event_hash)
);
CREATE INDEX idx_supplier_callback_pending ON supplier_callback_events(processed,created_at);
