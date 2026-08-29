-- User-center foundation. V1/V2/V3 are immutable migration history.

-- Existing data is normalized before the case-insensitive unique index is
-- created. The old column-level unique constraint is replaced by the
-- normalized expression index below.
ALTER TABLE users DROP CONSTRAINT IF EXISTS users_email_key;
UPDATE users
SET email = lower(btrim(email));

ALTER TABLE users
    ADD COLUMN user_no bigint;

-- Backfill ordinary users deterministically. Administrators intentionally do
-- not consume business numbers and keep user_no NULL.
WITH ranked_users AS (
    SELECT id,
           (10000 + row_number() OVER (ORDER BY created_at ASC, id ASC) - 1)::bigint AS assigned_user_no
    FROM users
    WHERE role = 'USER'
)
UPDATE users AS u
SET user_no = ranked_users.assigned_user_no
FROM ranked_users
WHERE u.id = ranked_users.id;

ALTER TABLE users
    ADD CONSTRAINT users_user_no_role_check
    CHECK ((role = 'ADMIN' AND user_no IS NULL)
        OR (role = 'USER' AND user_no IS NOT NULL AND user_no > 0));

CREATE UNIQUE INDEX uq_users_user_no_user
    ON users(user_no)
    WHERE role = 'USER';

CREATE UNIQUE INDEX uq_users_email_normalized
    ON users(lower(btrim(email)));

-- A singleton row is the durable source of truth for future assignments and
-- settings. Registration locks this row with SELECT ... FOR UPDATE.
CREATE TABLE user_number_sequences (
    id integer PRIMARY KEY,
    start_user_no bigint NOT NULL DEFAULT 10000,
    next_user_no bigint NOT NULL DEFAULT 10000,
    CONSTRAINT user_number_sequences_singleton CHECK (id = 1),
    CONSTRAINT user_number_sequences_start_positive CHECK (start_user_no > 0),
    CONSTRAINT user_number_sequences_next_valid CHECK (next_user_no >= start_user_no)
);

INSERT INTO user_number_sequences(id, start_user_no, next_user_no)
SELECT 1,
       10000,
       10000 + COUNT(*)::bigint
FROM users
WHERE role = 'USER';

CREATE TABLE balance_transactions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    user_id uuid NOT NULL,
    type varchar(32) NOT NULL
        CHECK (type IN ('ADMIN_ADJUSTMENT', 'RECHARGE', 'ORDER_PAYMENT', 'REFUND')),
    amount numeric(12,2) NOT NULL CHECK (amount > 0),
    delta numeric(12,2) NOT NULL CHECK (delta <> 0),
    balance_before numeric(12,2) NOT NULL CHECK (balance_before >= 0),
    balance_after numeric(12,2) NOT NULL CHECK (balance_after >= 0),
    reason varchar(500) NOT NULL,
    operator_id uuid NOT NULL,
    order_id uuid
);

CREATE INDEX idx_balance_transactions_user_created
    ON balance_transactions(user_id, created_at DESC);
CREATE INDEX idx_balance_transactions_operator_created
    ON balance_transactions(operator_id, created_at DESC);

CREATE INDEX idx_operation_logs_user_target_created
    ON operation_logs(target_type, target_id, created_at DESC);
