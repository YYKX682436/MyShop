-- Make the administrator refund reason optional without weakening any other
-- refund-ledger invariant, and keep an immutable inventory history for keys
-- that are deliberately returned to the sellable pool.
ALTER TABLE balance_transactions
    DROP CONSTRAINT IF EXISTS balance_transactions_refund_contract_check;

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
               OR (reason IS NOT NULL
                   AND char_length(btrim(reason)) NOT BETWEEN 1 AND 500))
    ) THEN
        RAISE EXCEPTION 'Cannot add the optional refund reason ledger contract';
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
            AND (reason IS NULL
                 OR char_length(btrim(reason)) BETWEEN 1 AND 500))
    );

CREATE TABLE card_key_reclaims (
    id uuid PRIMARY KEY,
    card_key_id uuid NOT NULL,
    order_id uuid NOT NULL,
    order_item_id uuid,
    product_id uuid NOT NULL,
    spec_id uuid,
    operator_id uuid NOT NULL,
    original_sold_at timestamp,
    reclaimed_at timestamp NOT NULL,
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_card_key_reclaims_order_card_key UNIQUE (order_id, card_key_id)
);

CREATE INDEX idx_card_key_reclaims_order
    ON card_key_reclaims(order_id, reclaimed_at);
