-- Payment channels persist only provider credentials and endpoint config.
-- Callback and return URLs are derived from the canonical public site URL at
-- payment creation time and are intentionally not administrator settings.
ALTER TABLE payment_channels
    ADD COLUMN payment_hint varchar(160),
    ADD COLUMN badge_text varchar(16);

ALTER TABLE payment_channels
    ADD CONSTRAINT payment_channels_payment_hint_length_check
        CHECK (payment_hint IS NULL OR char_length(payment_hint) <= 160),
    ADD CONSTRAINT payment_channels_badge_text_length_check
        CHECK (badge_text IS NULL OR char_length(badge_text) <= 16);

-- The project is in its new-development phase, but normalize the old label if
-- a pre-V15 development database contains it. This does not add a runtime
-- alias: all application/provider contracts use wxpay from this migration on.
UPDATE payment_channels
SET payment_type = 'wxpay'
WHERE lower(payment_type) = 'wechat';

-- Remove the retired administrator-managed URL fields from existing JSON
-- objects. Invalid legacy JSON is left untouched so the application can
-- report it as an unavailable channel instead of making the migration fail.
DO $$
DECLARE
    channel_row record;
    normalized jsonb;
BEGIN
    FOR channel_row IN
        SELECT id, config_data
        FROM payment_channels
        WHERE config_data IS NOT NULL
    LOOP
        BEGIN
            normalized := channel_row.config_data::jsonb;
            IF jsonb_typeof(normalized) = 'object' THEN
                UPDATE payment_channels
                SET config_data = (normalized - 'notify_url' - 'return_url')::text
                WHERE id = channel_row.id;
            END IF;
        EXCEPTION WHEN others THEN
            NULL;
        END;
    END LOOP;
END $$;
