ALTER TABLE order_items
    ADD COLUMN product_cover_url TEXT;

-- A missing product or an empty historical cover is valid legacy data. Keep
-- those snapshots NULL instead of making the migration fail.
UPDATE order_items AS item
SET product_cover_url = NULLIF(BTRIM(product.cover_url), '')
FROM products AS product
WHERE product.id = item.product_id
  AND NULLIF(BTRIM(product.cover_url), '') IS NOT NULL;

COMMENT ON COLUMN order_items.product_cover_url IS
    'Product cover URL captured when the order item was created; nullable for legacy rows';

CREATE OR REPLACE FUNCTION prevent_order_item_product_cover_update()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.product_cover_url IS DISTINCT FROM OLD.product_cover_url THEN
        RAISE EXCEPTION 'order item product_cover_url is immutable';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_order_items_product_cover_url_immutable
    BEFORE UPDATE OF product_cover_url ON order_items
    FOR EACH ROW EXECUTE FUNCTION prevent_order_item_product_cover_update();
