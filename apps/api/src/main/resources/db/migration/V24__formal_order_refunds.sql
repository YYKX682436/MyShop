-- Formalize the one-way administrator refund transition. Refunds are
-- represented by the order state and an immutable positive balance ledger
-- entry; delivered inventory is never made available again.
ALTER TABLE orders
    ADD COLUMN refunded_at timestamp;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM orders
        WHERE status NOT IN ('PENDING', 'PAID', 'DELIVERED', 'EXPIRED', 'REFUNDED')
    ) THEN
        RAISE EXCEPTION 'Cannot add the formal order status contract';
    END IF;
END
$$;

ALTER TABLE orders
    ADD CONSTRAINT orders_status_check CHECK (
        status IN ('PENDING', 'PAID', 'DELIVERED', 'EXPIRED', 'REFUNDED')
    );

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM balance_transactions
        WHERE type = 'REFUND'
          AND (amount <= 0
               OR delta <= 0
               OR amount <> delta
               OR balance_after <> balance_before + delta
               OR order_id IS NULL
               OR reason IS NULL
               OR char_length(btrim(reason)) NOT BETWEEN 3 AND 500)
    ) THEN
        RAISE EXCEPTION 'Cannot add the formal refund ledger contract';
    END IF;
END
$$;

ALTER TABLE balance_transactions
    ADD CONSTRAINT balance_transactions_refund_contract_check CHECK (
        type <> 'REFUND'
        OR (amount > 0
            AND delta > 0
            AND amount = delta
            AND balance_after = balance_before + delta
            AND order_id IS NOT NULL
            AND reason IS NOT NULL
            AND char_length(btrim(reason)) BETWEEN 3 AND 500)
    );

CREATE UNIQUE INDEX uq_balance_transactions_order_refund
    ON balance_transactions(order_id)
    WHERE type = 'REFUND' AND order_id IS NOT NULL;

COMMENT ON COLUMN orders.refunded_at IS
    'Time of the atomic administrator refund transition; delivered content is not recalled';
