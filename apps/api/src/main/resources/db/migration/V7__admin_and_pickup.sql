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

ALTER TABLE orders
    ADD COLUMN order_type varchar(32) NOT NULL DEFAULT 'NORMAL';

ALTER TABLE orders
    ADD CONSTRAINT orders_order_type_check CHECK (order_type IN ('NORMAL', 'ADMIN_PICKUP'));

CREATE UNIQUE INDEX uq_users_single_super_admin
    ON users (role)
    WHERE role = 'SUPER_ADMIN';

CREATE INDEX idx_orders_type_created
    ON orders (order_type, created_at DESC);
