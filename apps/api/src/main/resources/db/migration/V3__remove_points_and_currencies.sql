-- The V1/V2 files are immutable history. This migration converges an existing
-- installation to the fixed-CNY, balance-only model without carrying points
-- into the new balance column.

ALTER TABLE users
    ADD COLUMN balance numeric(12,2) NOT NULL DEFAULT 0.00;

ALTER TABLE users
    ADD CONSTRAINT users_balance_non_negative CHECK (balance >= 0);

DROP TABLE points_logs;

ALTER TABLE users
    DROP COLUMN points;

ALTER TABLE orders
    DROP COLUMN points_deducted,
    DROP COLUMN points_discount;

ALTER TABLE products
    DROP COLUMN currency;

DROP TABLE currencies;

DELETE FROM site_configs
WHERE config_key IN ('points_enabled', 'points_rate');
