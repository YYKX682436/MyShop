-- Administrator roles, nullable administrator contact details, and the
-- explicit order type used to keep internal pickups out of sales metrics.
ALTER TABLE users
    ALTER COLUMN email DROP NOT NULL;

ALTER TABLE users
    DROP CONSTRAINT IF EXISTS users_user_no_role_check;

ALTER TABLE users
    ADD CONSTRAINT users_user_no_role_check CHECK (
        (role = 'USER' AND user_no IS NOT NULL AND user_no > 0)
        OR (role IN ('ADMIN', 'SUPER_ADMIN') AND user_no IS NULL)
    );

-- V1 already created this column. Older data may use the former direct/cart
-- source values, so normalize those values before narrowing the type. Unknown
-- values fail closed instead of being silently reclassified.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM orders
        WHERE order_type IS NULL
           OR upper(btrim(order_type)) NOT IN ('NORMAL', 'DIRECT', 'CART', 'ADMIN_PICKUP')
    ) THEN
        RAISE EXCEPTION
            'Cannot normalize orders.order_type: unsupported historical value exists';
    END IF;
END
$$;

ALTER TABLE orders
    DROP CONSTRAINT IF EXISTS orders_order_type_check;

UPDATE orders
SET order_type = CASE
    WHEN upper(btrim(order_type)) = 'ADMIN_PICKUP' THEN 'ADMIN_PICKUP'
    ELSE 'NORMAL'
END;

ALTER TABLE orders
    ALTER COLUMN order_type TYPE varchar(32)
        USING order_type::varchar(32),
    ALTER COLUMN order_type SET DEFAULT 'NORMAL',
    ALTER COLUMN order_type SET NOT NULL;

ALTER TABLE orders
    ADD CONSTRAINT orders_order_type_check CHECK (order_type IN ('NORMAL', 'ADMIN_PICKUP'));

CREATE UNIQUE INDEX uq_users_single_super_admin
    ON users (role)
    WHERE role = 'SUPER_ADMIN';

DROP INDEX IF EXISTS idx_orders_type_created;
CREATE INDEX idx_orders_type_created
    ON orders (order_type, created_at DESC);
