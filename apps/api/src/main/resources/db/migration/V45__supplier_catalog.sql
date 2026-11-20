-- Supplier inventory is a separate source and cannot be consumed as local keys.
ALTER TABLE products DROP CONSTRAINT products_stock_mode_check;
ALTER TABLE products ADD CONSTRAINT products_stock_mode_check
    CHECK (stock_mode IN ('CARD_KEY', 'MANUAL', 'UNLIMITED', 'SUPPLIER'));

CREATE TABLE supplier_accounts (
    id uuid PRIMARY KEY,
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    name varchar(120) NOT NULL,
    provider_type varchar(32) NOT NULL,
    base_url varchar(500) NOT NULL,
    app_id varchar(120) NOT NULL,
    encrypted_secret text NOT NULL,
    enabled boolean NOT NULL DEFAULT true
);

CREATE TABLE supplier_product_mappings (
    id uuid PRIMARY KEY,
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    account_id uuid NOT NULL REFERENCES supplier_accounts(id),
    remote_goods_id varchar(120) NOT NULL,
    remote_sku varchar(255) NOT NULL DEFAULT '',
    product_id uuid NOT NULL REFERENCES products(id),
    spec_id uuid REFERENCES product_specs(id),
    goods_type integer NOT NULL,
    sku_type integer NOT NULL,
    cost numeric(24,8) NOT NULL CHECK (cost >= 0),
    stock bigint NOT NULL CHECK (stock >= 0),
    goods_status integer NOT NULL,
    sku_status integer NOT NULL,
    multiple integer NOT NULL CHECK (multiple > 0),
    min_quantity integer NOT NULL CHECK (min_quantity >= 0),
    max_quantity integer NOT NULL CHECK (max_quantity >= 0),
    snapshot jsonb NOT NULL,
    last_synced_at timestamp NOT NULL,
    last_attempt_at timestamp NOT NULL,
    sync_error varchar(200),
    CONSTRAINT uq_supplier_mapping_remote UNIQUE (account_id, remote_goods_id, remote_sku)
);
CREATE UNIQUE INDEX uq_supplier_mapping_local ON supplier_product_mappings
    (product_id, COALESCE(spec_id, '00000000-0000-0000-0000-000000000000'::uuid));
CREATE INDEX idx_supplier_mapping_sync ON supplier_product_mappings(account_id, last_attempt_at);
