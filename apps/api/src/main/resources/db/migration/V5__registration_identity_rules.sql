-- Registration identity rules. V1-V4 are immutable migration history.
-- Phone is nullable for historical administrators, while normal registration
-- supplies and validates it in the API. The database constraints below are a
-- second line of defense for persisted values and concurrent requests.

ALTER TABLE users
    ADD COLUMN phone varchar(11);

-- Replace the old column-level username uniqueness with the case-insensitive
-- expression index required by the registration/login contract. Normalize
-- surrounding whitespace before the preflight so the stored value matches the
-- service's canonical form without silently choosing a winner on conflict.
ALTER TABLE users DROP CONSTRAINT IF EXISTS users_username_key;

UPDATE users
SET username = btrim(username)
WHERE username <> btrim(username);

DO $$
BEGIN
    IF EXISTS (
        SELECT lower(username)
        FROM users
        GROUP BY lower(username)
        HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION
            'Cannot create uq_users_username_ci: existing case-insensitive username conflict';
    END IF;
END
$$;

CREATE UNIQUE INDEX uq_users_username_ci
    ON users(lower(username));

ALTER TABLE users
    ADD CONSTRAINT users_phone_format_check
    CHECK (phone IS NULL OR phone ~ '^1[3-9][0-9]{9}$');

CREATE UNIQUE INDEX uq_users_phone
    ON users(phone)
    WHERE phone IS NOT NULL;
