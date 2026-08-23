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
    currency varchar(10) DEFAULT 'CNY',
    category_id uuid NOT NULL,
    delivery_type varchar(10) DEFAULT 'AUTO',
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
    total_count integer NOT NULL DEFAULT 0,
    success_count integer NOT NULL DEFAULT 0,
    fail_count integer NOT NULL DEFAULT 0,
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
    is_risk_flagged boolean NOT NULL DEFAULT false,
    client_ip varchar(255),
    session_token varchar(255),
    payment_url text,
    qrcode_url text,
    epay_trade_no varchar(255)
);

CREATE TABLE order_items (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    order_id uuid NOT NULL,
    product_id uuid NOT NULL,
    product_title varchar(255) NOT NULL,
    spec_id uuid,
    spec_name varchar(255),
    quantity integer NOT NULL,
    unit_price numeric(10,2) NOT NULL,
    subtotal numeric(10,2) NOT NULL
);

CREATE TABLE card_keys (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    product_id uuid NOT NULL,
    spec_id uuid,
    content text NOT NULL,
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
    quantity integer NOT NULL
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

CREATE UNIQUE INDEX uq_card_key_pool_content ON card_keys(product_id, spec_id, content);
CREATE INDEX idx_card_keys_available_pool ON card_keys(product_id, spec_id, status, created_at);
CREATE INDEX idx_order_items_order ON order_items(order_id);
CREATE INDEX idx_orders_lookup_token_hash ON orders(lookup_token_hash);
CREATE INDEX idx_orders_status_expires ON orders(status, expires_at);
CREATE INDEX idx_orders_user_created ON orders(user_id, created_at DESC);
CREATE INDEX idx_orders_payment_status ON orders(payment_method, status, created_at DESC);
CREATE INDEX idx_orders_email_status ON orders(email, status);
CREATE INDEX idx_webhook_events_order ON webhook_events(order_id);
