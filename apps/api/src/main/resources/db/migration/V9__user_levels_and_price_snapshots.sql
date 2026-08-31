-- Final user-level pricing model and immutable order price snapshots.
-- V1-V8 are immutable history. All current-schema changes belong here.

CREATE TABLE user_levels (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    name varchar(100) NOT NULL,
    pricing_type varchar(32) NOT NULL
        CHECK (pricing_type IN ('RETAIL', 'COST_MARKUP')),
    markup_rate numeric(7,4),
    is_default boolean NOT NULL DEFAULT false,
    enabled boolean NOT NULL DEFAULT true,
    sort_order integer NOT NULL DEFAULT 0,
    remark varchar(500),
    CONSTRAINT user_levels_pricing_markup_check CHECK (
        (pricing_type = 'RETAIL' AND markup_rate IS NULL)
        OR (pricing_type = 'COST_MARKUP' AND markup_rate IS NOT NULL AND markup_rate >= 0)
    ),
    CONSTRAINT user_levels_default_enabled_check CHECK (NOT is_default OR enabled)
);

CREATE UNIQUE INDEX uq_user_levels_name_ci
    ON user_levels (lower(btrim(name)));

CREATE UNIQUE INDEX uq_user_levels_single_default
    ON user_levels (is_default)
    WHERE is_default = true;

INSERT INTO user_levels (name, pricing_type, markup_rate, enabled, is_default, sort_order, remark)
VALUES ('普通会员', 'RETAIL', NULL, true, true, 0, NULL);

ALTER TABLE users
    ADD COLUMN user_level_id uuid;

UPDATE users AS u
SET user_level_id = level.id
FROM user_levels AS level
WHERE u.role = 'USER'
  AND level.is_default = true;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM users
        WHERE role = 'USER' AND user_level_id IS NULL
    ) THEN
        RAISE EXCEPTION 'Every ordinary user must be assigned to the default user level';
    END IF;
END
$$;

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
    ALTER COLUMN cost_price DROP NOT NULL,
    ADD CONSTRAINT products_cost_price_non_negative CHECK (cost_price IS NULL OR cost_price >= 0),
    ADD CONSTRAINT products_retail_price_positive CHECK (retail_price > 0),
    ADD CONSTRAINT products_root_cost_required_without_specs CHECK (spec_enabled OR cost_price IS NOT NULL);

ALTER TABLE product_specs
    RENAME COLUMN price TO retail_price;

ALTER TABLE product_specs
    ADD COLUMN cost_price numeric(10,2);

UPDATE product_specs
SET cost_price = retail_price;

ALTER TABLE product_specs
    ALTER COLUMN cost_price SET NOT NULL,
    ADD CONSTRAINT product_specs_cost_price_non_negative CHECK (cost_price >= 0),
    ADD CONSTRAINT product_specs_retail_price_positive CHECK (retail_price > 0),
    ADD CONSTRAINT product_specs_product_id_id_key UNIQUE (product_id, id);

CREATE TABLE product_level_price_overrides (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    product_id uuid NOT NULL,
    spec_id uuid,
    user_level_id uuid NOT NULL,
    price numeric(10,2) NOT NULL CHECK (price > 0),
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

CREATE INDEX idx_product_level_price_overrides_level_product
    ON product_level_price_overrides (user_level_id, product_id);

-- Cross-row invariants cannot be expressed with a CHECK constraint. These
-- triggers make direct SQL writes obey the same mode and retail-cap rules as
-- the admin aggregate service.
CREATE OR REPLACE FUNCTION validate_product_level_price_override()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    product_spec_enabled boolean;
    retail numeric(10,2);
BEGIN
    SELECT p.spec_enabled, p.retail_price
    INTO product_spec_enabled, retail
    FROM products p
    WHERE p.id = NEW.product_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Override product does not exist';
    END IF;

    IF NEW.spec_id IS NULL THEN
        IF product_spec_enabled THEN
            RAISE EXCEPTION 'Spec-enabled products only accept spec-level overrides';
        END IF;
    ELSE
        IF NOT product_spec_enabled THEN
            RAISE EXCEPTION 'Products without specs only accept product-level overrides';
        END IF;
        SELECT s.retail_price INTO retail
        FROM product_specs s
        WHERE s.id = NEW.spec_id AND s.product_id = NEW.product_id;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'Override spec does not belong to the product';
        END IF;
    END IF;

    IF NEW.price > retail THEN
        RAISE EXCEPTION 'Override price cannot exceed retail price';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_validate_product_level_price_override
    BEFORE INSERT OR UPDATE ON product_level_price_overrides
    FOR EACH ROW EXECUTE FUNCTION validate_product_level_price_override();

CREATE OR REPLACE FUNCTION validate_product_retail_against_overrides()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM product_level_price_overrides o
        WHERE o.product_id = NEW.id
          AND ((NEW.spec_enabled AND o.spec_id IS NULL)
               OR (NOT NEW.spec_enabled AND o.spec_id IS NOT NULL))
    ) THEN
        RAISE EXCEPTION 'Override target does not match the product specification mode';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM product_level_price_overrides o
        LEFT JOIN product_specs s ON s.product_id = o.product_id AND s.id = o.spec_id
        WHERE o.product_id = NEW.id
          AND o.price > CASE WHEN o.spec_id IS NULL THEN NEW.retail_price ELSE s.retail_price END
    ) THEN
        RAISE EXCEPTION 'Retail price is below an existing level override';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_validate_product_retail_against_overrides
    AFTER UPDATE OF retail_price, spec_enabled ON products
    FOR EACH ROW EXECUTE FUNCTION validate_product_retail_against_overrides();

CREATE OR REPLACE FUNCTION validate_spec_retail_against_overrides()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM product_level_price_overrides o
        WHERE o.spec_id = NEW.id AND o.price > NEW.retail_price
    ) THEN
        RAISE EXCEPTION 'Retail price is below an existing spec override';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_validate_spec_retail_against_overrides
    AFTER UPDATE OF retail_price ON product_specs
    FOR EACH ROW EXECUTE FUNCTION validate_spec_retail_against_overrides();

ALTER TABLE orders
    ADD COLUMN user_level_id_snapshot uuid,
    ADD COLUMN user_level_name_snapshot varchar(100);

-- Deliberately do not backfill these columns. Historical orders must remain
-- NULL because the user's current level is not evidence of their old level.

ALTER TABLE order_items
    ADD COLUMN cost_price_snapshot numeric(10,2),
    ADD COLUMN retail_price_snapshot numeric(10,2),
    ADD COLUMN pricing_source varchar(32);

UPDATE order_items
SET cost_price_snapshot = unit_price,
    retail_price_snapshot = unit_price,
    pricing_source = 'RETAIL';

ALTER TABLE order_items
    ALTER COLUMN cost_price_snapshot SET NOT NULL,
    ALTER COLUMN retail_price_snapshot SET NOT NULL,
    ALTER COLUMN pricing_source SET DEFAULT 'RETAIL',
    ALTER COLUMN pricing_source SET NOT NULL,
    ADD CONSTRAINT order_items_cost_price_snapshot_non_negative CHECK (cost_price_snapshot >= 0),
    ADD CONSTRAINT order_items_retail_price_snapshot_positive CHECK (retail_price_snapshot > 0),
    ADD CONSTRAINT order_items_pricing_source_check CHECK (
        pricing_source IN ('RETAIL', 'LEVEL_MARKUP', 'LEVEL_OVERRIDE')
    );

DROP TABLE IF EXISTS wholesale_rules;

ALTER TABLE products
    DROP COLUMN IF EXISTS wholesale_enabled;
