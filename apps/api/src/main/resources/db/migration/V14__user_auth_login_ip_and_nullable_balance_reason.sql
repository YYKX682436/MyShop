ALTER TABLE users
    ADD COLUMN auth_version integer NOT NULL DEFAULT 0;

ALTER TABLE users
    ADD COLUMN last_login_ip varchar(45);

ALTER TABLE users
    ADD COLUMN last_login_at timestamp;

ALTER TABLE balance_transactions
    ALTER COLUMN reason DROP NOT NULL;
