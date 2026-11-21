ALTER TABLE orders ADD COLUMN subsite_id uuid REFERENCES subsites(id);
ALTER TABLE orders ADD COLUMN subsite_owner_id_snapshot uuid REFERENCES users(id);
ALTER TABLE orders ADD COLUMN subsite_profit numeric(12,2);
ALTER TABLE orders ADD COLUMN subsite_profit_state varchar(16);
ALTER TABLE orders ADD COLUMN subsite_profit_canceled_at timestamp;
ALTER TABLE orders ADD COLUMN subsite_profit_settled_at timestamp;
ALTER TABLE orders ADD COLUMN subsite_profit_reversed_at timestamp;
ALTER TABLE orders ADD CONSTRAINT orders_subsite_snapshot_check CHECK (
  (subsite_id IS NULL AND subsite_owner_id_snapshot IS NULL AND subsite_profit IS NULL
    AND subsite_profit_state IS NULL AND subsite_profit_canceled_at IS NULL
    AND subsite_profit_settled_at IS NULL AND subsite_profit_reversed_at IS NULL)
  OR (subsite_id IS NOT NULL AND subsite_owner_id_snapshot IS NOT NULL AND subsite_profit IS NOT NULL AND subsite_profit >= 0
    AND ((subsite_profit_state IS NULL AND subsite_profit_settled_at IS NULL AND subsite_profit_reversed_at IS NULL AND subsite_profit_canceled_at IS NULL)
      OR (subsite_profit_state IS NOT NULL AND ((subsite_profit_state = 'PENDING' AND subsite_profit_settled_at IS NULL AND subsite_profit_reversed_at IS NULL AND subsite_profit_canceled_at IS NULL)
      OR (subsite_profit_state = 'SETTLED' AND subsite_profit_settled_at IS NOT NULL AND subsite_profit_reversed_at IS NULL AND subsite_profit_canceled_at IS NULL)
      OR (subsite_profit_state = 'REVERSED' AND subsite_profit_settled_at IS NOT NULL AND subsite_profit_reversed_at IS NOT NULL AND subsite_profit_canceled_at IS NULL)
      OR (subsite_profit_state = 'CANCELED' AND subsite_profit_settled_at IS NULL AND subsite_profit_reversed_at IS NULL AND subsite_profit_canceled_at IS NOT NULL)))))
);
CREATE INDEX idx_orders_subsite_created ON orders(subsite_id, created_at DESC);
CREATE INDEX idx_orders_subsite_profit_pending ON orders(id) WHERE subsite_profit_state = 'PENDING';
ALTER TABLE order_items ADD COLUMN subsite_supply_price_snapshot numeric(10,2)
  CHECK (subsite_supply_price_snapshot >= 0);

ALTER TABLE balance_transactions DROP CONSTRAINT balance_transactions_type_check;
ALTER TABLE balance_transactions ADD CONSTRAINT balance_transactions_type_check CHECK
  (type IN ('ADMIN_ADJUSTMENT', 'RECHARGE', 'ORDER_PAYMENT', 'REFUND', 'SUBSITE_PROFIT', 'SUBSITE_PROFIT_REVERSAL', 'SUBSITE_OPENING'));
ALTER TABLE balance_transactions ADD CONSTRAINT balance_transactions_subsite_profit_check CHECK (
  type NOT IN ('SUBSITE_PROFIT', 'SUBSITE_PROFIT_REVERSAL') OR
  (order_id IS NOT NULL AND balance_after = balance_before + delta AND amount = abs(delta)
    AND ((type = 'SUBSITE_PROFIT' AND delta > 0) OR (type = 'SUBSITE_PROFIT_REVERSAL' AND delta < 0)))
);
CREATE UNIQUE INDEX uq_balance_transactions_subsite_profit ON balance_transactions(order_id, type)
  WHERE type IN ('SUBSITE_PROFIT', 'SUBSITE_PROFIT_REVERSAL');
