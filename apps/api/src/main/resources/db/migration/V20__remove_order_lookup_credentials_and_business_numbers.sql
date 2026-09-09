-- The final order contract uses the UUID primary key and the browser's
-- session_token ownership. Historical migrations remain immutable; this
-- migration removes their retired schema objects from the live database.

DROP TRIGGER IF EXISTS trg_orders_order_no_immutable ON orders;
DROP FUNCTION IF EXISTS prevent_order_no_update();

ALTER TABLE orders
    DROP CONSTRAINT IF EXISTS orders_order_no_key,
    DROP CONSTRAINT IF EXISTS orders_order_no_positive;

DROP INDEX IF EXISTS idx_orders_lookup_token_hash;

ALTER TABLE orders
    DROP COLUMN IF EXISTS lookup_token_hash,
    DROP COLUMN IF EXISTS guest_lookup_token,
    DROP COLUMN IF EXISTS order_no;

DROP SEQUENCE IF EXISTS order_no_sequence;

-- V12's relation index is still required by the single-order card-key view.
CREATE INDEX IF NOT EXISTS idx_card_keys_order ON card_keys(order_id);
