-- The V1 table and column are historical migration state. Remove them from
-- the current schema without deleting any orders or order items.
DROP TABLE IF EXISTS cart_items;
ALTER TABLE orders DROP COLUMN IF EXISTS order_type;
