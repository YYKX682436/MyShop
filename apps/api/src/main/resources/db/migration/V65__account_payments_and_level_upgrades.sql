-- Account purchases share the existing payment provider and attempt state
-- machine without becoming product orders.

ALTER TABLE user_levels
    ADD COLUMN upgrade_rank integer,
    ADD COLUMN self_upgrade_enabled boolean NOT NULL DEFAULT false,
    ADD COLUMN upgrade_price numeric(12,2),
    ADD COLUMN public_price_enabled boolean NOT NULL DEFAULT false;

WITH ranked AS (
    SELECT id, row_number() OVER (
        ORDER BY is_default DESC, sort_order ASC, created_at ASC, id ASC
    ) * 100 - 100 AS rank
    FROM user_levels
)
UPDATE user_levels level
SET upgrade_rank = ranked.rank
FROM ranked
WHERE ranked.id = level.id;

UPDATE user_levels SET upgrade_price = 0 WHERE is_default = true;

ALTER TABLE user_levels
    ALTER COLUMN upgrade_rank SET NOT NULL,
    ADD CONSTRAINT user_levels_upgrade_price_non_negative
        CHECK (upgrade_price IS NULL OR upgrade_price >= 0),
    ADD CONSTRAINT user_levels_default_upgrade_price_check
        CHECK (NOT is_default OR upgrade_price = 0),
    ADD CONSTRAINT user_levels_default_self_upgrade_check
        CHECK (NOT is_default OR NOT self_upgrade_enabled),
    ADD CONSTRAINT user_levels_public_upgrade_check
        CHECK (NOT public_price_enabled OR (self_upgrade_enabled AND upgrade_price IS NOT NULL));
CREATE UNIQUE INDEX uq_user_levels_upgrade_rank ON user_levels(upgrade_rank);

-- A subsite has no inherited paid upgrade rules. Missing rows remain closed.
CREATE TABLE subsite_level_upgrade_rules (
    subsite_id uuid NOT NULL REFERENCES subsites(id) ON DELETE CASCADE,
    user_level_id uuid NOT NULL REFERENCES user_levels(id),
    self_upgrade_enabled boolean NOT NULL DEFAULT false,
    upgrade_price numeric(12,2),
    public_price_enabled boolean NOT NULL DEFAULT false,
    updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (subsite_id, user_level_id),
    CONSTRAINT subsite_level_upgrade_price_non_negative
        CHECK (upgrade_price IS NULL OR upgrade_price >= 0),
    CONSTRAINT subsite_public_upgrade_check
        CHECK (NOT public_price_enabled OR (self_upgrade_enabled AND upgrade_price IS NOT NULL))
);

CREATE TABLE account_payment_transactions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    user_id uuid NOT NULL REFERENCES users(id),
    subsite_id uuid REFERENCES subsites(id),
    subsite_owner_id_snapshot uuid REFERENCES users(id),
    type varchar(24) NOT NULL CHECK (type IN ('RECHARGE', 'LEVEL_UPGRADE')),
    payment_method varchar(16) NOT NULL CHECK (payment_method IN ('BALANCE', 'ONLINE')),
    amount numeric(12,2) NOT NULL CHECK (amount >= 0),
    status varchar(24) NOT NULL CHECK (status IN ('PENDING', 'COMPLETED', 'RECOVERED', 'FAILED', 'EXPIRED')),
    current_level_id uuid REFERENCES user_levels(id),
    target_level_id uuid REFERENCES user_levels(id),
    current_level_rank_snapshot integer,
    target_level_rank_snapshot integer,
    current_level_price_snapshot numeric(12,2),
    target_level_price_snapshot numeric(12,2),
    channel_code_snapshot varchar(128),
    recovery_reason varchar(500),
    expires_at timestamp,
    completed_at timestamp,
    CONSTRAINT account_payment_subsite_owner_snapshot_check CHECK (
        (subsite_id IS NULL AND subsite_owner_id_snapshot IS NULL)
        OR (subsite_id IS NOT NULL AND subsite_owner_id_snapshot IS NOT NULL)
    ),
    CONSTRAINT account_payment_type_snapshot_check CHECK (
        (type = 'RECHARGE' AND payment_method = 'ONLINE' AND amount > 0
            AND current_level_id IS NULL AND target_level_id IS NULL
            AND current_level_rank_snapshot IS NULL AND target_level_rank_snapshot IS NULL
            AND current_level_price_snapshot IS NULL AND target_level_price_snapshot IS NULL)
        OR
        (type = 'LEVEL_UPGRADE' AND current_level_id IS NOT NULL AND target_level_id IS NOT NULL
            AND current_level_rank_snapshot IS NOT NULL AND target_level_rank_snapshot IS NOT NULL
            AND current_level_price_snapshot IS NOT NULL AND target_level_price_snapshot IS NOT NULL
            AND target_level_rank_snapshot > current_level_rank_snapshot
            AND amount = target_level_price_snapshot - current_level_price_snapshot)
    )
);
CREATE INDEX idx_account_payment_user_created
    ON account_payment_transactions(user_id, created_at DESC);
CREATE INDEX idx_account_payment_recharge_status
    ON account_payment_transactions(type, status, created_at DESC);
CREATE UNIQUE INDEX uq_account_payment_pending_level_upgrade
    ON account_payment_transactions(user_id)
    WHERE type = 'LEVEL_UPGRADE' AND status = 'PENDING';

-- Payment attempts now bind to exactly one existing order or account payment.
ALTER TABLE payment_attempts
    ALTER COLUMN order_id DROP NOT NULL,
    ALTER COLUMN amount TYPE numeric(12,2) USING amount::numeric(12,2),
    ADD COLUMN account_payment_transaction_id uuid REFERENCES account_payment_transactions(id),
    ADD CONSTRAINT payment_attempt_business_reference_check CHECK (
        (order_id IS NOT NULL AND account_payment_transaction_id IS NULL)
        OR (order_id IS NULL AND account_payment_transaction_id IS NOT NULL)
    );
CREATE INDEX idx_payment_attempt_account_transaction
    ON payment_attempts(account_payment_transaction_id, created_at DESC)
    WHERE account_payment_transaction_id IS NOT NULL;
CREATE UNIQUE INDEX uq_payment_attempt_unresolved_account_transaction
    ON payment_attempts(account_payment_transaction_id)
    WHERE account_payment_transaction_id IS NOT NULL
      AND creation_status IN ('INTENT', 'SUBMITTING', 'UNKNOWN') AND status = 'PENDING';

ALTER TABLE webhook_events ALTER COLUMN order_id DROP NOT NULL;

ALTER TABLE balance_transactions
    ADD COLUMN account_payment_transaction_id uuid REFERENCES account_payment_transactions(id);
ALTER TABLE balance_transactions DROP CONSTRAINT balance_transactions_type_check;
ALTER TABLE balance_transactions ADD CONSTRAINT balance_transactions_type_check CHECK
    (type IN ('ADMIN_ADJUSTMENT', 'RECHARGE', 'ORDER_PAYMENT', 'REFUND',
              'SUBSITE_PROFIT', 'SUBSITE_PROFIT_REVERSAL', 'SUBSITE_OPENING',
              'LEVEL_UPGRADE', 'SUBSITE_UPGRADE_PROFIT'));
ALTER TABLE balance_transactions DROP CONSTRAINT balance_transactions_delta_check;
ALTER TABLE balance_transactions ADD CONSTRAINT balance_transactions_delta_check
    CHECK (delta <> 0 OR type = 'LEVEL_UPGRADE');
ALTER TABLE balance_transactions DROP CONSTRAINT balance_transactions_amount_check;
ALTER TABLE balance_transactions ADD CONSTRAINT balance_transactions_amount_check CHECK (
    amount > 0 OR (type = 'LEVEL_UPGRADE' AND amount = 0 AND delta = 0
                   AND account_payment_transaction_id IS NOT NULL)
);
ALTER TABLE balance_transactions ADD CONSTRAINT balance_transactions_account_payment_check CHECK (
    (type NOT IN ('LEVEL_UPGRADE', 'SUBSITE_UPGRADE_PROFIT')
        OR account_payment_transaction_id IS NOT NULL)
    AND (type <> 'SUBSITE_UPGRADE_PROFIT'
        OR (order_id IS NULL AND delta > 0 AND amount = delta AND balance_after = balance_before + delta))
    AND (type <> 'LEVEL_UPGRADE' OR order_id IS NULL)
);
CREATE UNIQUE INDEX uq_balance_transactions_account_payment_type
    ON balance_transactions(account_payment_transaction_id, type)
    WHERE account_payment_transaction_id IS NOT NULL;

-- Safe financial defaults: no recharge until explicitly enabled; higher
-- levels remain private and non-purchasable until an operator configures them.
INSERT INTO site_configs(config_key, config_value, config_group)
VALUES ('recharge_enabled', 'false', 'finance'),
       ('recharge_min_amount', '10.00', 'finance'),
       ('recharge_max_amount', '5000.00', 'finance')
ON CONFLICT (config_key) DO NOTHING;
