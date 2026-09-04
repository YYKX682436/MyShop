-- Order contact email was never a durable business attribute. Registered
-- account email remains available through users.email when notifications are
-- enabled; guest orders deliberately have no email destination.

DROP INDEX IF EXISTS idx_orders_email_status;

ALTER TABLE orders
    DROP COLUMN email;
