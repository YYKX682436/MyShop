-- Replace the deployed V10 string business numbers with numeric BIGINT values.
-- V10 is deployed history and must remain immutable; this migration is the
-- only forward path from product_code/product_code_sequence to product_no.

ALTER TABLE products
    ADD COLUMN product_no BIGINT;

ALTER TABLE order_items
    ADD COLUMN product_no BIGINT;

UPDATE products
SET product_no = substring(product_code FROM 2)::bigint;

UPDATE order_items AS item
SET product_no = product.product_no
FROM products AS product
WHERE product.id = item.product_id;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM products
        WHERE product_no IS NULL OR product_no <= 0
    ) THEN
        RAISE EXCEPTION 'Cannot backfill products.product_no with a positive value';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM order_items
        WHERE product_no IS NULL OR product_no <= 0
    ) THEN
        RAISE EXCEPTION 'Cannot backfill order_items.product_no with a positive value';
    END IF;
END
$$;

ALTER TABLE products
    DROP CONSTRAINT products_product_code_format,
    DROP CONSTRAINT products_product_code_key;

ALTER TABLE order_items
    DROP CONSTRAINT order_items_product_code_format;

DROP TRIGGER IF EXISTS trg_products_product_code_immutable ON products;
DROP TRIGGER IF EXISTS trg_order_items_product_code_immutable ON order_items;
DROP FUNCTION IF EXISTS prevent_product_code_update();

ALTER SEQUENCE product_code_sequence RENAME TO product_no_sequence;

-- V10 already advanced this sequence, but recomputing from the preserved
-- numeric values also handles explicit historical values safely.
SELECT setval(
    'product_no_sequence',
    COALESCE((SELECT max(product_no) FROM products), 0) + 1,
    false
);

ALTER TABLE products
    ALTER COLUMN product_no SET DEFAULT nextval('product_no_sequence'::regclass),
    ALTER COLUMN product_no SET NOT NULL,
    ADD CONSTRAINT products_product_no_positive CHECK (product_no > 0),
    ADD CONSTRAINT products_product_no_key UNIQUE (product_no);

ALTER TABLE order_items
    ALTER COLUMN product_no SET NOT NULL,
    ADD CONSTRAINT order_items_product_no_positive CHECK (product_no > 0);

ALTER TABLE products
    DROP COLUMN product_code;

ALTER TABLE order_items
    DROP COLUMN product_code;

CREATE OR REPLACE FUNCTION prevent_product_no_update()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.product_no IS DISTINCT FROM OLD.product_no THEN
        RAISE EXCEPTION 'product_no is immutable';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_products_product_no_immutable
    BEFORE UPDATE OF product_no ON products
    FOR EACH ROW EXECUTE FUNCTION prevent_product_no_update();

CREATE TRIGGER trg_order_items_product_no_immutable
    BEFORE UPDATE OF product_no ON order_items
    FOR EACH ROW EXECUTE FUNCTION prevent_product_no_update();
