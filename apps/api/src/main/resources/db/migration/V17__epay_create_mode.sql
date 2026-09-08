-- Epay mapi.php API creation and submit.php page creation are independent
-- protocols. Convert the retired per-channel switch into the explicit mode
-- and remove the retired key from Epay channel configuration.
DO $$
DECLARE
    channel_row record;
    normalized jsonb;
    create_mode text;
    legacy_key text := 'submit_' || 'fallback_enabled';
BEGIN
    FOR channel_row IN
        SELECT id, config_data
        FROM payment_channels
        WHERE lower(provider_type) = 'epay'
    LOOP
        BEGIN
            normalized := COALESCE(channel_row.config_data::jsonb, '{}'::jsonb);
            IF jsonb_typeof(normalized) = 'object' THEN
                create_mode := CASE
                    WHEN lower(trim(COALESCE(normalized ->> legacy_key, 'false'))) = 'true'
                        THEN 'page'
                    ELSE 'api'
                END;

                UPDATE payment_channels
                SET config_data = (
                    (normalized - legacy_key)
                    || jsonb_build_object('create_mode', create_mode)
                )::text
                WHERE id = channel_row.id;
            END IF;
        EXCEPTION WHEN others THEN
            -- Keep an invalid legacy object visible to the application so it
            -- can report the channel as unavailable instead of aborting the
            -- whole schema migration.
            NULL;
        END;
    END LOOP;
END $$;
