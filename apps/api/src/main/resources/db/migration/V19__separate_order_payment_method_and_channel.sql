-- Orders now distinguish the payment method (customer balance or an online
-- payment) from the selected online channel. ADMIN_PICKUP is represented only
-- by order_type and has no payment fields.
ALTER TABLE orders
    ADD COLUMN payment_channel_code varchar(128);

-- Before V19, NORMAL orders stored the online channel code in payment_method.
-- Preserve that value in the new column before replacing the method marker.
UPDATE orders
SET payment_channel_code = NULLIF(BTRIM(payment_method), '')
WHERE order_type = 'NORMAL';

UPDATE orders
SET payment_method = 'ONLINE'
WHERE order_type = 'NORMAL';

UPDATE orders
SET payment_method = NULL,
    payment_channel_code = NULL
WHERE order_type = 'ADMIN_PICKUP';

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM orders
        WHERE order_type = 'NORMAL'
          AND (payment_method IS NULL
               OR payment_method NOT IN ('BALANCE', 'ONLINE')
               OR (payment_method = 'ONLINE'
                   AND (payment_channel_code IS NULL OR BTRIM(payment_channel_code) = ''))
               OR (payment_method = 'BALANCE' AND payment_channel_code IS NOT NULL))
    ) THEN
        RAISE EXCEPTION 'Cannot backfill orders payment method/channel contract';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM orders
        WHERE order_type = 'ADMIN_PICKUP'
          AND (payment_method IS NOT NULL OR payment_channel_code IS NOT NULL)
    ) THEN
        RAISE EXCEPTION 'Cannot clean ADMIN_PICKUP payment fields';
    END IF;
END $$;

ALTER TABLE orders
    ADD CONSTRAINT orders_payment_method_channel_check CHECK (
        (order_type = 'ADMIN_PICKUP'
            AND payment_method IS NULL
            AND payment_channel_code IS NULL)
        OR
        (order_type = 'NORMAL'
            AND payment_method IN ('BALANCE', 'ONLINE')
            AND (
                (payment_method = 'ONLINE'
                    AND payment_channel_code IS NOT NULL
                    AND BTRIM(payment_channel_code) <> '')
                OR
                (payment_method = 'BALANCE'
                    AND payment_channel_code IS NULL)
            ))
    );

CREATE INDEX idx_orders_payment_method_channel_status
    ON orders(payment_method, payment_channel_code, status, created_at DESC);

-- The database, not an application-side existence check, enforces one debit
-- ledger entry for one order.
CREATE UNIQUE INDEX uq_balance_transactions_order_payment
    ON balance_transactions(order_id)
    WHERE type = 'ORDER_PAYMENT' AND order_id IS NOT NULL;
