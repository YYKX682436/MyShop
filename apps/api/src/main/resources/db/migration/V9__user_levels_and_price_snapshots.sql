-- User-level pricing foundation and immutable order price snapshots.
-- Phase 1 creates the data model only; pricing calculation and management APIs
-- remain intentionally outside this migration.

CREATE TABLE user_levels (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    name varchar(100) NOT NULL,
    pricing_type varchar(32) NOT NULL
        CHECK (pricing_type IN ('RETAIL', 'MARKUP')),
    markup_rate numeric(7,4) NOT NULL DEFAULT 0.0000
        CHECK (markup_rate >= 0),
    enabled boolean NOT NULL DEFAULT true,
    is_default boolean NOT NULL DEFAULT false
);

CREATE UNIQUE INDEX uq_user_levels_single_default
    ON user_levels (is_default)
    WHERE is_default = true;

INSERT INTO user_levels (name, pricing_type, markup_rate, enabled, is_default)
VALUES ('普通会员', 'RETAIL', 0.0000, true, true);

ALTER TABLE users
    ADD COLUMN user_level_id uuid;

UPDATE users AS u
SET user_level_id = level.id
FROM user_levels AS level
WHERE u.role = 'USER'
  AND level.is_default = true;

ALTER TABLE users
    ADD CONSTRAINT users_user_level_fk
        FOREIGN KEY (user_level_id) REFERENCES user_levels(id),
    ADD CONSTRAINT users_user_level_role_check CHECK (
        (role = 'USER' AND user_level_id IS NOT NULL)
        OR (role IN ('ADMIN', 'SUPER_ADMIN') AND user_level_id IS NULL)
    );

ALTER TABLE products
    RENAME COLUMN base_price TO retail_price;

ALTER TABLE products
    ADD COLUMN cost_price numeric(10,2);

UPDATE products
SET cost_price = retail_price;

ALTER TABLE products
    ALTER COLUMN cost_price SET NOT NULL,
    ADD CONSTRAINT products_cost_price_non_negative CHECK (cost_price >= 0),
    ADD CONSTRAINT products_retail_price_non_negative CHECK (retail_price >= 0);

ALTER TABLE product_specs
    RENAME COLUMN price TO retail_price;

ALTER TABLE product_specs
    ADD COLUMN cost_price numeric(10,2);

UPDATE product_specs
SET cost_price = retail_price;

ALTER TABLE product_specs
    ALTER COLUMN cost_price SET NOT NULL,
    ADD CONSTRAINT product_specs_cost_price_non_negative CHECK (cost_price >= 0),
    ADD CONSTRAINT product_specs_retail_price_non_negative CHECK (retail_price >= 0),
    ADD CONSTRAINT product_specs_product_id_id_key UNIQUE (product_id, id);

CREATE TABLE product_level_price_overrides (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    product_id uuid NOT NULL,
    spec_id uuid,
    user_level_id uuid NOT NULL,
    price numeric(10,2) NOT NULL CHECK (price >= 0),
    CONSTRAINT product_level_price_overrides_product_fk
        FOREIGN KEY (product_id) REFERENCES products(id),
    CONSTRAINT product_level_price_overrides_level_fk
        FOREIGN KEY (user_level_id) REFERENCES user_levels(id),
    CONSTRAINT product_level_price_overrides_spec_fk
        FOREIGN KEY (product_id, spec_id) REFERENCES product_specs(product_id, id)
);

CREATE UNIQUE INDEX uq_product_level_price_overrides_no_spec
    ON product_level_price_overrides (product_id, user_level_id)
    WHERE spec_id IS NULL;

CREATE UNIQUE INDEX uq_product_level_price_overrides_with_spec
    ON product_level_price_overrides (product_id, spec_id, user_level_id)
    WHERE spec_id IS NOT NULL;

CREATE INDEX idx_product_level_price_overrides_level
    ON product_level_price_overrides (user_level_id, product_id);

ALTER TABLE orders
    ADD COLUMN user_level_id uuid,
    ADD COLUMN user_level_name varchar(100),
    ADD COLUMN user_level_pricing_type varchar(32),
    ADD COLUMN user_level_markup_rate numeric(7,4);

UPDATE orders AS o
SET user_level_id = level.id,
    user_level_name = level.name,
    user_level_pricing_type = level.pricing_type,
    user_level_markup_rate = level.markup_rate
FROM users AS u
JOIN user_levels AS level ON level.id = u.user_level_id
WHERE o.user_id = u.id;

ALTER TABLE orders
    ADD CONSTRAINT orders_user_level_pricing_type_check CHECK (
        user_level_pricing_type IS NULL
        OR user_level_pricing_type IN ('RETAIL', 'MARKUP')
    ),
    ADD CONSTRAINT orders_user_level_markup_rate_check CHECK (
        user_level_markup_rate IS NULL OR user_level_markup_rate >= 0
    );

ALTER TABLE order_items
    ADD COLUMN cost_price numeric(10,2),
    ADD COLUMN retail_price numeric(10,2),
    ADD COLUMN pricing_source varchar(32);

UPDATE order_items
SET cost_price = unit_price,
    retail_price = unit_price,
    pricing_source = 'RETAIL';

ALTER TABLE order_items
    ALTER COLUMN cost_price SET NOT NULL,
    ALTER COLUMN retail_price SET NOT NULL,
    ALTER COLUMN pricing_source SET DEFAULT 'RETAIL',
    ALTER COLUMN pricing_source SET NOT NULL,
    ADD CONSTRAINT order_items_cost_price_non_negative CHECK (cost_price >= 0),
    ADD CONSTRAINT order_items_retail_price_non_negative CHECK (retail_price >= 0),
    ADD CONSTRAINT order_items_pricing_source_check CHECK (
        pricing_source IN ('RETAIL', 'LEVEL_OVERRIDE', 'LEVEL_MARKUP')
    );

DROP TABLE wholesale_rules;

ALTER TABLE products
    DROP COLUMN wholesale_enabled;
