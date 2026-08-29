CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE users (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    username varchar(255) NOT NULL UNIQUE,
    email varchar(255) NOT NULL UNIQUE,
    password_hash varchar(255) NOT NULL,
    role varchar(255) NOT NULL DEFAULT 'USER',
    points integer NOT NULL DEFAULT 0,
    is_deleted integer NOT NULL DEFAULT 0,
    failed_login_attempts integer NOT NULL DEFAULT 0,
    lock_until timestamp
);

CREATE TABLE product_categories (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    name varchar(255) NOT NULL,
    sort_order integer NOT NULL DEFAULT 0,
    is_deleted integer NOT NULL DEFAULT 0
);

CREATE TABLE products (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    title varchar(255) NOT NULL,
    description text,
    detail_md text,
    cover_url varchar(255),
    base_price numeric(10,2) NOT NULL,
    currency varchar(10) NOT NULL DEFAULT 'CNY',
    category_id uuid NOT NULL,
    delivery_type varchar(10) NOT NULL DEFAULT 'AUTO'
        CHECK (delivery_type IN ('AUTO', 'MANUAL')),
    customer_input_fields jsonb NOT NULL DEFAULT '[]'::jsonb
        CHECK (jsonb_typeof(customer_input_fields) = 'array'),
    stock_mode varchar(32) NOT NULL DEFAULT 'CARD_KEY'
        CHECK (stock_mode IN ('CARD_KEY', 'MANUAL', 'UNLIMITED')),
    low_stock_threshold integer NOT NULL DEFAULT 10,
    wholesale_enabled boolean NOT NULL DEFAULT false,
    spec_enabled boolean NOT NULL DEFAULT false,
    is_enabled boolean NOT NULL DEFAULT true,
    initial_sales integer NOT NULL DEFAULT 0,
    sort_order integer NOT NULL DEFAULT 0,
    is_deleted integer NOT NULL DEFAULT 0
);

CREATE TABLE product_specs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    product_id uuid NOT NULL,
    name varchar(255) NOT NULL,
    price numeric(10,2) NOT NULL,
    is_visible boolean NOT NULL DEFAULT true,
    sort_order integer NOT NULL DEFAULT 0,
    is_deleted integer NOT NULL DEFAULT 0
);

CREATE TABLE wholesale_rules (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    product_id uuid NOT NULL,
    spec_id uuid,
    min_quantity integer NOT NULL,
    unit_price numeric(10,2) NOT NULL
);

CREATE TABLE card_import_batches (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    product_id uuid NOT NULL,
    spec_id uuid,
    imported_by uuid NOT NULL,
    total_count integer NOT NULL DEFAULT 0 CHECK (total_count >= 0),
    success_count integer NOT NULL DEFAULT 0 CHECK (success_count >= 0),
    duplicate_count integer NOT NULL DEFAULT 0 CHECK (duplicate_count >= 0),
    fail_count integer NOT NULL DEFAULT 0 CHECK (fail_count >= 0),
    column_count integer NOT NULL DEFAULT 1 CHECK (column_count >= 1),
    check_duplicate boolean NOT NULL DEFAULT true,
    fail_detail text
);

CREATE TABLE orders (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    user_id uuid,
    email varchar(255),
    total_amount numeric(10,2),
    actual_amount numeric(10,2),
    status varchar(255) NOT NULL,
    order_type varchar(255) NOT NULL,
    payment_method varchar(64),
    points_deducted integer NOT NULL DEFAULT 0,
    points_discount numeric(10,2) NOT NULL DEFAULT 0,
    expires_at timestamp,
    paid_at timestamp,
    delivered_at timestamp,
    idempotency_key varchar(255) UNIQUE,
    lookup_token_hash varchar(64),
    guest_lookup_token varchar(128),
    idempotency_fingerprint varchar(64),
    is_risk_flagged boolean NOT NULL DEFAULT false,
    client_ip varchar(255),
    session_token varchar(255),
    payment_url text,
    qrcode_url text,
    epay_trade_no varchar(255)
);

COMMENT ON COLUMN orders.guest_lookup_token IS
    'Sensitive recoverable guest order credential. Never expose in admin lists or logs; protect database backups.';

COMMENT ON COLUMN orders.idempotency_fingerprint IS
    'Client checkout payload fingerprint used to reject replay with changed intent.';

CREATE TABLE order_items (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    order_id uuid NOT NULL,
    product_id uuid NOT NULL,
    product_title varchar(255) NOT NULL,
    spec_id uuid,
    spec_name varchar(255),
    quantity integer NOT NULL CHECK (quantity > 0),
    unit_price numeric(10,2) NOT NULL,
    subtotal numeric(10,2) NOT NULL,
    delivery_type varchar(10) NOT NULL DEFAULT 'AUTO'
        CHECK (delivery_type IN ('AUTO', 'MANUAL')),
    customer_inputs jsonb NOT NULL DEFAULT '[]'::jsonb
        CHECK (jsonb_typeof(customer_inputs) = 'array'),
    stock_mode varchar(32) NOT NULL DEFAULT 'CARD_KEY'
        CHECK (stock_mode IN ('CARD_KEY', 'MANUAL', 'UNLIMITED')),
    fulfillment_status varchar(32) NOT NULL DEFAULT 'PENDING'
        CHECK (fulfillment_status IN ('PENDING', 'WAITING_MANUAL', 'DELIVERED', 'CANCELLED')),
    delivered_at timestamp,
    delivery_notified_at timestamp,
    manual_stock_reserved integer NOT NULL DEFAULT 0
        CHECK (manual_stock_reserved >= 0)
);

CREATE TABLE card_keys (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    product_id uuid NOT NULL,
    spec_id uuid,
    content_parts jsonb NOT NULL
        CHECK (jsonb_typeof(content_parts) = 'array' AND jsonb_array_length(content_parts) > 0),
    content_fingerprint varchar(64) NOT NULL,
    status varchar(255) NOT NULL,
    order_id uuid,
    order_item_id uuid,
    import_batch_id uuid,
    sold_at timestamp
);

CREATE TABLE cart_items (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    user_id uuid,
    session_token varchar(255),
    product_id uuid NOT NULL,
    spec_id uuid,
    customer_inputs jsonb NOT NULL DEFAULT '[]'::jsonb
        CHECK (jsonb_typeof(customer_inputs) = 'array'),
    quantity integer NOT NULL CHECK (quantity > 0)
);

CREATE TABLE payment_channels (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    channel_code varchar(255) NOT NULL,
    channel_name varchar(255) NOT NULL,
    provider_type varchar(255) NOT NULL DEFAULT 'epay',
    payment_type varchar(32) NOT NULL DEFAULT 'alipay',
    config_data text,
    is_enabled boolean NOT NULL DEFAULT true,
    sort_order integer NOT NULL DEFAULT 0,
    is_deleted integer NOT NULL DEFAULT 0
);

CREATE TABLE currencies (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    code varchar(10) NOT NULL UNIQUE,
    name varchar(50) NOT NULL,
    symbol varchar(10) NOT NULL,
    is_enabled boolean NOT NULL DEFAULT true,
    sort_order integer NOT NULL DEFAULT 0
);

CREATE TABLE site_configs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    config_key varchar(255) NOT NULL UNIQUE,
    config_value text,
    config_group varchar(255)
);

CREATE TABLE points_logs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    user_id uuid NOT NULL,
    change_amount integer NOT NULL DEFAULT 0,
    balance_after integer NOT NULL DEFAULT 0,
    reason varchar(255) NOT NULL,
    order_id uuid
);

CREATE TABLE operation_logs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    user_id uuid NOT NULL,
    username varchar(255),
    action varchar(255) NOT NULL,
    target_type varchar(255),
    target_id varchar(255),
    detail text,
    ip_address varchar(255)
);

CREATE TABLE visit_stats (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    visit_date date NOT NULL UNIQUE,
    pv integer NOT NULL DEFAULT 0,
    uv integer NOT NULL DEFAULT 0
);

CREATE TABLE webhook_events (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    event_id varchar(255) NOT NULL UNIQUE,
    channel_code varchar(255) NOT NULL,
    order_id uuid NOT NULL,
    payload text,
    process_result varchar(255),
    retry_count integer NOT NULL DEFAULT 0
);

CREATE TABLE manual_stocks (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    product_id uuid NOT NULL,
    spec_id uuid,
    available_quantity integer NOT NULL DEFAULT 0 CHECK (available_quantity >= 0),
    reserved_quantity integer NOT NULL DEFAULT 0 CHECK (reserved_quantity >= 0)
);

CREATE INDEX idx_card_keys_available_pool
    ON card_keys(product_id, spec_id, status, created_at);
CREATE INDEX idx_card_keys_pool_fingerprint
    ON card_keys(product_id, spec_id, content_fingerprint);
CREATE INDEX idx_order_items_order ON order_items(order_id);
CREATE INDEX idx_orders_lookup_token_hash ON orders(lookup_token_hash);
CREATE INDEX idx_orders_status_expires ON orders(status, expires_at);
CREATE INDEX idx_orders_user_created ON orders(user_id, created_at DESC);
CREATE INDEX idx_orders_payment_status ON orders(payment_method, status, created_at DESC);
CREATE INDEX idx_orders_email_status ON orders(email, status);
CREATE INDEX idx_orders_paid_fulfillment
    ON orders(status, delivered_at, created_at)
    WHERE status = 'PAID';
CREATE INDEX idx_webhook_events_order ON webhook_events(order_id);
CREATE UNIQUE INDEX uq_payment_channels_active_code
    ON payment_channels(channel_code)
    WHERE is_deleted = 0;
CREATE UNIQUE INDEX uq_manual_stocks_pool
    ON manual_stocks(product_id, COALESCE(spec_id, '00000000-0000-0000-0000-000000000000'::uuid));
CREATE INDEX idx_manual_stocks_product_spec
    ON manual_stocks(product_id, spec_id);
CREATE INDEX idx_cart_items_owner_product_spec
    ON cart_items(user_id, product_id, spec_id);
CREATE INDEX idx_cart_items_session_product_spec
    ON cart_items(session_token, product_id, spec_id);

INSERT INTO currencies (code, name, symbol, is_enabled, sort_order)
VALUES ('CNY', '人民币', '¥', true, 1)
ON CONFLICT (code) DO NOTHING;

INSERT INTO site_configs (config_key, config_value, config_group)
VALUES
    ('site_name', 'MyShop', 'basic'),
    ('site_slogan', '安全、清晰、可追踪的数字商品交付', 'basic'),
    ('site_description', 'MyShop 数字商品商店，订单、支付和卡密发货全链路可追踪。', 'basic'),
    ('announcement_enabled', 'false', 'announcement'),
    ('announcement', '', 'announcement'),
    ('popup_enabled', 'false', 'popup'),
    ('popup_content', '', 'popup'),
    ('maintenance_enabled', 'false', 'maintenance'),
    ('maintenance_message', '站点维护中，请稍后再试。', 'maintenance'),
    ('points_enabled', 'false', 'points'),
    ('points_rate', '0', 'points'),
    ('footer_text', 'MyShop 数字商品商店', 'basic'),
    ('contact_items', '[]', 'basic')
ON CONFLICT (config_key) DO NOTHING;
