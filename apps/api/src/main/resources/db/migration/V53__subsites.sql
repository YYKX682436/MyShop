CREATE TABLE subsites (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 owner_id uuid NOT NULL UNIQUE REFERENCES users(id),
 prefix varchar(63) NOT NULL UNIQUE CHECK (prefix ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'),
 name varchar(100) NOT NULL,
 enabled boolean NOT NULL DEFAULT true,
 logo_url varchar(500), customer_service varchar(500), announcement text,
 created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
);
ALTER TABLE users ADD COLUMN subsite_id uuid REFERENCES subsites(id);
CREATE INDEX idx_users_subsite ON users(subsite_id);
CREATE TABLE subsite_settings (
 id integer PRIMARY KEY CHECK (id = 1),
 self_service_enabled boolean NOT NULL DEFAULT false,
 opening_price numeric(12,2) NOT NULL DEFAULT 0 CHECK (opening_price >= 0),
 supply_level_id uuid REFERENCES user_levels(id)
);
INSERT INTO subsite_settings(id) VALUES (1);
CREATE TABLE subsite_allowed_levels (
 level_id uuid PRIMARY KEY REFERENCES user_levels(id)
);
CREATE TABLE subsite_opening_records (
 subsite_id uuid PRIMARY KEY REFERENCES subsites(id),
 owner_id uuid NOT NULL REFERENCES users(id),
 amount numeric(12,2) NOT NULL CHECK (amount >= 0),
 balance_before numeric(12,2) NOT NULL CHECK (balance_before >= 0),
 balance_after numeric(12,2) NOT NULL CHECK (balance_after >= 0),
 operator_id uuid NOT NULL REFERENCES users(id),
 balance_transaction_id uuid UNIQUE REFERENCES balance_transactions(id),
 created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
 CHECK (balance_after = balance_before - amount)
);

ALTER TABLE balance_transactions ADD CONSTRAINT balance_transactions_subsite_opening_check CHECK (
 type <> 'SUBSITE_OPENING' OR (order_id IS NULL AND amount > 0 AND delta = -amount AND balance_after = balance_before + delta)
);
