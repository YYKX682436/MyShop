-- Add a stable, human-facing order number while preserving orders.id as the
-- internal UUID primary key used by all relations and payment callbacks.

CREATE SEQUENCE order_no_sequence
    START WITH 1000000001
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER TABLE orders
    ADD COLUMN order_no BIGINT;

WITH numbered_orders AS (
    SELECT id,
           1000000000 + ROW_NUMBER() OVER (ORDER BY created_at ASC NULLS LAST, id ASC) AS generated_order_no
    FROM orders
)
UPDATE orders AS target
SET order_no = numbered_orders.generated_order_no
FROM numbered_orders
WHERE target.id = numbered_orders.id;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM orders
        WHERE order_no IS NULL OR order_no <= 0
    ) THEN
        RAISE EXCEPTION 'Cannot backfill orders.order_no with a positive value';
    END IF;
END
$$;

SELECT setval(
    'order_no_sequence',
    COALESCE((SELECT max(order_no) FROM orders), 1000000000) + 1,
    false
);

ALTER TABLE orders
    ALTER COLUMN order_no SET DEFAULT nextval('order_no_sequence'::regclass),
    ALTER COLUMN order_no SET NOT NULL,
    ADD CONSTRAINT orders_order_no_positive CHECK (order_no > 0),
    ADD CONSTRAINT orders_order_no_key UNIQUE (order_no);

ALTER SEQUENCE order_no_sequence OWNED BY orders.order_no;

CREATE OR REPLACE FUNCTION prevent_order_no_update()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.order_no IS DISTINCT FROM OLD.order_no THEN
        RAISE EXCEPTION 'order_no is immutable';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_orders_order_no_immutable
    BEFORE UPDATE OF order_no ON orders
    FOR EACH ROW EXECUTE FUNCTION prevent_order_no_update();

-- Public/admin detail loads use the order relation; this index avoids a
-- sequential scan when fetching the sold cards for one order.
CREATE INDEX IF NOT EXISTS idx_card_keys_order ON card_keys(order_id);
