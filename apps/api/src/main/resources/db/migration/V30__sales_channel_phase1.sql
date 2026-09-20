-- Generic sales-channel accounts, product listings, SKU mappings, and a
-- durable operation queue. Provider credentials are intentionally not stored.

CREATE TABLE sales_channel_accounts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    provider varchar(32) NOT NULL,
    provider_account_id varchar(128) NOT NULL,
    display_name varchar(255),
    status varchar(32) NOT NULL,
    is_enabled boolean NOT NULL DEFAULT true,
    capabilities jsonb NOT NULL DEFAULT '{}'::jsonb,
    last_verified_at timestamp,
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_sales_channel_accounts_provider_account
        UNIQUE (provider, provider_account_id),
    CONSTRAINT sales_channel_accounts_provider_check
        CHECK (provider IN ('XIANYU')),
    CONSTRAINT sales_channel_accounts_status_check
        CHECK (status IN ('CONNECTED', 'AUTH_REQUIRED', 'SUSPENDED', 'ERROR', 'DISABLED'))
);

CREATE TABLE sales_channel_listings (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    sales_channel_account_id uuid NOT NULL,
    product_id uuid NOT NULL,
    external_item_id varchar(128),
    desired_state varchar(16) NOT NULL DEFAULT 'OFFLINE',
    status varchar(32) NOT NULL DEFAULT 'DRAFT',
    desired_revision bigint NOT NULL DEFAULT 0,
    applied_revision bigint NOT NULL DEFAULT 0,
    provider_config jsonb NOT NULL DEFAULT '{}'::jsonb,
    applied_fingerprint varchar(64),
    last_synced_at timestamp,
    last_error_code varchar(128),
    last_error_message text,
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_sales_channel_listings_account
        FOREIGN KEY (sales_channel_account_id) REFERENCES sales_channel_accounts(id),
    CONSTRAINT fk_sales_channel_listings_product
        FOREIGN KEY (product_id) REFERENCES products(id),
    CONSTRAINT uq_sales_channel_listings_account_product
        UNIQUE (sales_channel_account_id, product_id),
    CONSTRAINT sales_channel_listings_desired_state_check
        CHECK (desired_state IN ('ONLINE', 'OFFLINE')),
    CONSTRAINT sales_channel_listings_status_check
        CHECK (status IN ('DRAFT', 'PUBLISHING', 'ONLINE', 'SYNCING', 'OFFLINING', 'OFFLINE', 'ERROR', 'UNKNOWN')),
    CONSTRAINT sales_channel_listings_revisions_check
        CHECK (desired_revision >= 0 AND applied_revision >= 0 AND applied_revision <= desired_revision)
);

CREATE INDEX idx_sales_channel_listings_product
    ON sales_channel_listings(product_id);
CREATE INDEX idx_sales_channel_listings_account_status
    ON sales_channel_listings(sales_channel_account_id, status);
CREATE INDEX idx_sales_channel_listings_external_item
    ON sales_channel_listings(external_item_id);

CREATE OR REPLACE FUNCTION prevent_sales_channel_listing_revision_regression()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.desired_revision < OLD.desired_revision
       OR NEW.applied_revision < OLD.applied_revision THEN
        RAISE EXCEPTION 'Sales channel listing revisions cannot move backwards';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_sales_channel_listings_revision_monotonic
    BEFORE UPDATE OF desired_revision, applied_revision ON sales_channel_listings
    FOR EACH ROW EXECUTE FUNCTION prevent_sales_channel_listing_revision_regression();

CREATE TABLE sales_channel_listing_specs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    sales_channel_listing_id uuid NOT NULL,
    product_spec_id uuid NOT NULL,
    external_sku_id varchar(128),
    provider_mapping jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_sales_channel_listing_specs_listing
        FOREIGN KEY (sales_channel_listing_id) REFERENCES sales_channel_listings(id),
    CONSTRAINT fk_sales_channel_listing_specs_product_spec
        FOREIGN KEY (product_spec_id) REFERENCES product_specs(id),
    CONSTRAINT uq_sales_channel_listing_specs_listing_spec
        UNIQUE (sales_channel_listing_id, product_spec_id)
);

CREATE INDEX idx_sales_channel_listing_specs_product_spec
    ON sales_channel_listing_specs(product_spec_id);

CREATE TABLE sales_channel_operations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    sales_channel_listing_id uuid NOT NULL,
    operation_type varchar(16) NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'PENDING',
    desired_revision bigint NOT NULL,
    idempotency_key varchar(255) NOT NULL,
    payload jsonb NOT NULL DEFAULT '{}'::jsonb,
    result jsonb,
    attempt_count integer NOT NULL DEFAULT 0,
    next_attempt_at timestamp,
    started_at timestamp,
    finished_at timestamp,
    last_error_code varchar(128),
    last_error_message text,
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_sales_channel_operations_listing
        FOREIGN KEY (sales_channel_listing_id) REFERENCES sales_channel_listings(id),
    CONSTRAINT uq_sales_channel_operations_idempotency_key
        UNIQUE (idempotency_key),
    CONSTRAINT sales_channel_operations_type_check
        CHECK (operation_type IN ('PUBLISH', 'SYNC', 'OFFLINE', 'DELETE', 'RECONCILE')),
    CONSTRAINT sales_channel_operations_status_check
        CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'UNKNOWN', 'SUPERSEDED')),
    CONSTRAINT sales_channel_operations_attempt_count_check
        CHECK (attempt_count >= 0),
    CONSTRAINT sales_channel_operations_revision_check
        CHECK (desired_revision >= 0)
);

CREATE INDEX idx_sales_channel_operations_worker
    ON sales_channel_operations(status, next_attempt_at, created_at);
CREATE INDEX idx_sales_channel_operations_listing_revision
    ON sales_channel_operations(sales_channel_listing_id, desired_revision);
CREATE UNIQUE INDEX uq_sales_channel_operations_running_listing
    ON sales_channel_operations(sales_channel_listing_id)
    WHERE status = 'RUNNING';

COMMENT ON TABLE sales_channel_accounts IS
    'Sales channel account references and capabilities only; never stores provider credentials';
COMMENT ON COLUMN sales_channel_accounts.provider_account_id IS
    'Stable provider account identifier, not a provider database row id';
COMMENT ON TABLE sales_channel_operations IS
    'Durable sales channel command outbox; UNKNOWN outcomes require reconciliation before further publish attempts';
