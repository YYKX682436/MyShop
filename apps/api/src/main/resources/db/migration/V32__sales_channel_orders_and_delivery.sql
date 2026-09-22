-- Phase 4: durable sales-channel order import and delivery operations.
-- This migration deliberately leaves V1-V31 immutable.

ALTER TABLE orders
    DROP CONSTRAINT IF EXISTS orders_order_type_check;
ALTER TABLE orders
    ADD CONSTRAINT orders_order_type_check
    CHECK (order_type IN ('NORMAL', 'ADMIN_PICKUP', 'SALES_CHANNEL'));

ALTER TABLE orders
    DROP CONSTRAINT IF EXISTS orders_payment_method_channel_check;
ALTER TABLE orders
    ADD CONSTRAINT orders_payment_method_channel_check CHECK (
        (order_type = 'ADMIN_PICKUP'
            AND payment_method IS NULL
            AND payment_channel_code IS NULL)
        OR
        (order_type = 'SALES_CHANNEL'
            AND payment_method IS NULL
            AND payment_channel_code = 'XIANYU')
        OR
        (order_type = 'NORMAL'
            AND payment_method IN ('BALANCE', 'ONLINE')
            AND (
                (payment_method = 'ONLINE'
                    AND payment_channel_code IS NOT NULL
                    AND BTRIM(payment_channel_code) <> '')
                OR
                (payment_method = 'BALANCE'
                    AND payment_channel_code IS NULL)
            ))
    );

ALTER TABLE order_items
    DROP CONSTRAINT IF EXISTS order_items_pricing_source_check;
ALTER TABLE order_items
    ADD CONSTRAINT order_items_pricing_source_check
    CHECK (pricing_source IN ('RETAIL', 'LEVEL_MARKUP', 'LEVEL_OVERRIDE', 'SALES_CHANNEL'));

ALTER TABLE sales_channel_accounts
    ADD COLUMN order_sync_enabled boolean NOT NULL DEFAULT false;

ALTER TABLE sales_channel_listings
    ADD COLUMN auto_fulfillment_enabled boolean NOT NULL DEFAULT false;

CREATE TABLE sales_channel_orders (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    provider varchar(32) NOT NULL,
    sales_channel_account_id uuid NOT NULL,
    sales_channel_listing_id uuid,
    order_id uuid UNIQUE,
    external_order_no varchar(128) NOT NULL,
    external_item_id varchar(128) NOT NULL,
    external_sku_id varchar(128),
    external_spec_name varchar(128),
    external_spec_value varchar(128),
    quantity integer NOT NULL,
    amount numeric(12,2) NOT NULL,
    currency varchar(8) NOT NULL,
    external_status varchar(32) NOT NULL,
    source_updated_at timestamp NOT NULL,
    source_fingerprint varchar(64) NOT NULL,
    ingest_status varchar(32) NOT NULL DEFAULT 'DISCOVERED',
    delivery_status varchar(32) NOT NULL DEFAULT 'NOT_READY',
    last_error_code varchar(128),
    last_error_message text,
    auto_fulfillment_requested boolean NOT NULL DEFAULT false,
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_sales_channel_orders_account
        FOREIGN KEY (sales_channel_account_id) REFERENCES sales_channel_accounts(id),
    CONSTRAINT fk_sales_channel_orders_listing
        FOREIGN KEY (sales_channel_listing_id) REFERENCES sales_channel_listings(id),
    CONSTRAINT fk_sales_channel_orders_order
        FOREIGN KEY (order_id) REFERENCES orders(id),
    CONSTRAINT uq_sales_channel_orders_external
        UNIQUE (provider, sales_channel_account_id, external_order_no),
    CONSTRAINT sales_channel_orders_quantity_check CHECK (quantity > 0),
    CONSTRAINT sales_channel_orders_ingest_check CHECK (
        ingest_status IN ('DISCOVERED', 'IMPORTED', 'MAPPING_ERROR', 'SOURCE_CONFLICT', 'UNSUPPORTED')
    ),
    CONSTRAINT sales_channel_orders_delivery_check CHECK (
        delivery_status IN ('NOT_READY', 'FULFILLMENT_PENDING', 'FULFILLED', 'MESSAGE_PENDING',
                            'MESSAGE_SENDING', 'MESSAGE_UNKNOWN', 'CONFIRM_PENDING',
                            'CONFIRM_UNKNOWN', 'SUCCEEDED', 'FAILED', 'REVIEW_REQUIRED')
    )
);

CREATE INDEX idx_sales_channel_orders_sync
    ON sales_channel_orders(sales_channel_account_id, source_updated_at, external_order_no);
CREATE INDEX idx_sales_channel_orders_status
    ON sales_channel_orders(ingest_status, delivery_status, updated_at);

CREATE TABLE sales_channel_order_cursors (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    provider varchar(32) NOT NULL,
    sales_channel_account_id uuid NOT NULL,
    cursor_updated_at timestamp,
    cursor_id bigint,
    initialized_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_sales_channel_order_cursors_account
        FOREIGN KEY (sales_channel_account_id) REFERENCES sales_channel_accounts(id),
    CONSTRAINT uq_sales_channel_order_cursors_account
        UNIQUE (provider, sales_channel_account_id)
);

CREATE TABLE sales_channel_delivery_operations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    sales_channel_order_id uuid NOT NULL,
    operation_type varchar(32) NOT NULL,
    unit_index integer,
    card_key_id uuid,
    status varchar(16) NOT NULL DEFAULT 'PENDING',
    request_id varchar(255) NOT NULL,
    payload_fingerprint varchar(64) NOT NULL,
    attempt_count integer NOT NULL DEFAULT 0,
    next_attempt_at timestamp,
    started_at timestamp,
    finished_at timestamp,
    result_json jsonb,
    last_error_code varchar(128),
    last_error_message text,
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_sales_channel_delivery_order
        FOREIGN KEY (sales_channel_order_id) REFERENCES sales_channel_orders(id),
    CONSTRAINT fk_sales_channel_delivery_card
        FOREIGN KEY (card_key_id) REFERENCES card_keys(id),
    CONSTRAINT uq_sales_channel_delivery_request UNIQUE (request_id),
    CONSTRAINT sales_channel_delivery_type_check CHECK (
        operation_type IN ('SEND_MESSAGE', 'RECONCILE_MESSAGE', 'CONFIRM_DELIVERY', 'RECONCILE_CONFIRM')
    ),
    CONSTRAINT sales_channel_delivery_status_check CHECK (
        status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'UNKNOWN')
    )
);

CREATE UNIQUE INDEX uq_sales_channel_delivery_unit
    ON sales_channel_delivery_operations(sales_channel_order_id, operation_type, unit_index)
    WHERE operation_type = 'SEND_MESSAGE';
CREATE INDEX idx_sales_channel_delivery_worker
    ON sales_channel_delivery_operations(status, next_attempt_at, created_at);
