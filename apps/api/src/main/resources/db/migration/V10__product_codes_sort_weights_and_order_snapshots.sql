-- Product business numbers, product-level weight semantics, and immutable
-- order-item product-code snapshots. V1-V9 remain immutable history.

ALTER TABLE products
    RENAME COLUMN sort_order TO sort_weight;

ALTER TABLE products
    ALTER COLUMN sort_weight SET DEFAULT 0;

CREATE SEQUENCE product_code_sequence
    AS bigint
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER TABLE products
    ADD COLUMN product_code varchar(32);

-- Existing products receive deterministic numbers. The sequence is advanced
-- below the update so new concurrent inserts continue after the backfill.
WITH numbered_products AS (
    SELECT id,
           row_number() OVER (ORDER BY created_at ASC, id ASC) AS number
    FROM products
)
UPDATE products AS product
SET product_code = 'P' || lpad(numbered_products.number::text, 6, '0')
FROM numbered_products
WHERE product.id = numbered_products.id;

SELECT setval(
    'product_code_sequence',
    COALESCE((SELECT max(substring(product_code FROM 2)::bigint) FROM products), 0) + 1,
    false
);

ALTER TABLE products
    ALTER COLUMN product_code SET DEFAULT (
        'P' || lpad(nextval('product_code_sequence'::regclass)::text, 6, '0')
    ),
    ALTER COLUMN product_code SET NOT NULL,
    ADD CONSTRAINT products_product_code_format
        CHECK (product_code ~ '^P[0-9]{6,}$'),
    ADD CONSTRAINT products_product_code_key UNIQUE (product_code);

ALTER TABLE order_items
    ADD COLUMN product_code varchar(32);

UPDATE order_items AS item
SET product_code = product.product_code
FROM products AS product
WHERE product.id = item.product_id;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM order_items WHERE product_code IS NULL) THEN
        RAISE EXCEPTION 'Cannot backfill order_items.product_code: an order item references a missing product';
    END IF;
END
$$;

ALTER TABLE order_items
    ALTER COLUMN product_code SET NOT NULL,
    ADD CONSTRAINT order_items_product_code_format
        CHECK (product_code ~ '^P[0-9]{6,}$');

-- ORM updatable=false is the normal write boundary; these triggers also keep
-- direct SQL writes from changing a business number or historical snapshot.
CREATE OR REPLACE FUNCTION prevent_product_code_update()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.product_code IS DISTINCT FROM OLD.product_code THEN
        RAISE EXCEPTION 'product_code is immutable';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_products_product_code_immutable
    BEFORE UPDATE OF product_code ON products
    FOR EACH ROW EXECUTE FUNCTION prevent_product_code_update();

CREATE TRIGGER trg_order_items_product_code_immutable
    BEFORE UPDATE OF product_code ON order_items
    FOR EACH ROW EXECUTE FUNCTION prevent_product_code_update();
