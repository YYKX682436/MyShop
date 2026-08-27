ALTER TABLE products
    ADD COLUMN IF NOT EXISTS customer_input_type varchar(32),
    ADD COLUMN IF NOT EXISTS stock_mode varchar(32);

UPDATE products
SET customer_input_type = COALESCE(NULLIF(customer_input_type, ''), 'NONE'),
    stock_mode = COALESCE(NULLIF(stock_mode, ''), 'CARD_KEY');

ALTER TABLE products
    ALTER COLUMN customer_input_type SET DEFAULT 'NONE',
    ALTER COLUMN customer_input_type SET NOT NULL,
    ALTER COLUMN stock_mode SET DEFAULT 'CARD_KEY',
    ALTER COLUMN stock_mode SET NOT NULL;

ALTER TABLE cart_items
    ADD COLUMN IF NOT EXISTS customer_input_value varchar(255);

ALTER TABLE order_items
    ADD COLUMN IF NOT EXISTS delivery_type varchar(10),
    ADD COLUMN IF NOT EXISTS customer_input_type varchar(32),
    ADD COLUMN IF NOT EXISTS customer_input_value varchar(255),
    ADD COLUMN IF NOT EXISTS stock_mode varchar(32),
    ADD COLUMN IF NOT EXISTS fulfillment_status varchar(32),
    ADD COLUMN IF NOT EXISTS delivered_at timestamp,
    ADD COLUMN IF NOT EXISTS manual_stock_reserved integer;

UPDATE order_items oi
SET delivery_type = COALESCE(NULLIF(oi.delivery_type, ''), 'AUTO'),
    customer_input_type = COALESCE(NULLIF(oi.customer_input_type, ''), 'NONE'),
    stock_mode = COALESCE(NULLIF(oi.stock_mode, ''), 'CARD_KEY'),
    fulfillment_status = CASE
        WHEN o.status = 'DELIVERED' THEN 'DELIVERED'
        ELSE COALESCE(NULLIF(oi.fulfillment_status, ''), 'PENDING')
    END,
    delivered_at = CASE
        WHEN o.status = 'DELIVERED' THEN COALESCE(oi.delivered_at, o.delivered_at)
        ELSE oi.delivered_at
    END,
    manual_stock_reserved = COALESCE(oi.manual_stock_reserved, 0)
FROM orders o
WHERE oi.order_id = o.id;

ALTER TABLE order_items
    ALTER COLUMN delivery_type SET DEFAULT 'AUTO',
    ALTER COLUMN delivery_type SET NOT NULL,
    ALTER COLUMN customer_input_type SET DEFAULT 'NONE',
    ALTER COLUMN customer_input_type SET NOT NULL,
    ALTER COLUMN stock_mode SET DEFAULT 'CARD_KEY',
    ALTER COLUMN stock_mode SET NOT NULL,
    ALTER COLUMN fulfillment_status SET DEFAULT 'PENDING',
    ALTER COLUMN fulfillment_status SET NOT NULL,
    ALTER COLUMN manual_stock_reserved SET DEFAULT 0,
    ALTER COLUMN manual_stock_reserved SET NOT NULL;

ALTER TABLE card_keys
    ADD COLUMN IF NOT EXISTS content_parts jsonb,
    ADD COLUMN IF NOT EXISTS content_fingerprint varchar(64);

UPDATE card_keys
SET content_parts = jsonb_build_array(content)
WHERE content_parts IS NULL;

UPDATE card_keys
SET content_fingerprint = encode(
        digest('1|' || octet_length(content)::text || ':' || content, 'sha256'),
        'hex'
    )
WHERE content_fingerprint IS NULL;

ALTER TABLE card_keys
    ALTER COLUMN content_parts SET DEFAULT '[]'::jsonb,
    ALTER COLUMN content_parts SET NOT NULL;

DROP INDEX IF EXISTS uq_card_key_pool_content;
CREATE INDEX IF NOT EXISTS idx_card_keys_pool_fingerprint
    ON card_keys(product_id, spec_id, content_fingerprint);

ALTER TABLE card_import_batches
    ADD COLUMN IF NOT EXISTS column_count integer,
    ADD COLUMN IF NOT EXISTS check_duplicate boolean,
    ADD COLUMN IF NOT EXISTS duplicate_count integer;

UPDATE card_import_batches
SET column_count = COALESCE(column_count, 1),
    check_duplicate = COALESCE(check_duplicate, true),
    duplicate_count = COALESCE(duplicate_count, 0);

ALTER TABLE card_import_batches
    ALTER COLUMN column_count SET DEFAULT 1,
    ALTER COLUMN column_count SET NOT NULL,
    ALTER COLUMN check_duplicate SET DEFAULT true,
    ALTER COLUMN check_duplicate SET NOT NULL,
    ALTER COLUMN duplicate_count SET DEFAULT 0,
    ALTER COLUMN duplicate_count SET NOT NULL;

CREATE TABLE IF NOT EXISTS manual_stocks (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    product_id uuid NOT NULL,
    spec_id uuid,
    available_quantity integer NOT NULL DEFAULT 0 CHECK (available_quantity >= 0),
    reserved_quantity integer NOT NULL DEFAULT 0 CHECK (reserved_quantity >= 0)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_manual_stocks_pool
    ON manual_stocks(product_id, COALESCE(spec_id, '00000000-0000-0000-0000-000000000000'::uuid));
CREATE INDEX IF NOT EXISTS idx_manual_stocks_product_spec
    ON manual_stocks(product_id, spec_id);
