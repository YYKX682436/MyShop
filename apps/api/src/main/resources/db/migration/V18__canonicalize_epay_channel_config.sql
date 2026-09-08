-- The Epay-compatible provider has exactly three administrator-managed
-- configuration fields. V17 was already released with a retired create_mode
-- marker, so this forward migration removes that marker and every other
-- undeclared field from existing Epay channel objects.
--
-- Invalid JSON and non-object values are deliberately left untouched. The
-- application will report those channels as unavailable rather than making a
-- destructive guess about credentials or endpoints.
DO $$
DECLARE
    channel_row record;
    stored jsonb;
    normalized jsonb;
    api_url_value jsonb;
    pid_value jsonb;
    key_value jsonb;
BEGIN
    FOR channel_row IN
        SELECT id, config_data
        FROM payment_channels
        WHERE lower(provider_type) = 'epay'
          AND config_data IS NOT NULL
    LOOP
        BEGIN
            stored := channel_row.config_data::jsonb;
            IF jsonb_typeof(stored) = 'object' THEN
                -- Prefer the canonical lower-case key. A case-insensitive
                -- fallback preserves credentials from early hand-written
                -- configurations without allowing duplicate output keys.
                SELECT entry.entry_value INTO api_url_value
                FROM jsonb_each(stored) AS entry(entry_key, entry_value)
                WHERE lower(entry.entry_key) = 'api_url'
                ORDER BY CASE WHEN entry.entry_key = 'api_url' THEN 0 ELSE 1 END, entry.entry_key
                LIMIT 1;

                SELECT entry.entry_value INTO pid_value
                FROM jsonb_each(stored) AS entry(entry_key, entry_value)
                WHERE lower(entry.entry_key) = 'pid'
                ORDER BY CASE WHEN entry.entry_key = 'pid' THEN 0 ELSE 1 END, entry.entry_key
                LIMIT 1;

                SELECT entry.entry_value INTO key_value
                FROM jsonb_each(stored) AS entry(entry_key, entry_value)
                WHERE lower(entry.entry_key) = 'key'
                ORDER BY CASE WHEN entry.entry_key = 'key' THEN 0 ELSE 1 END, entry.entry_key
                LIMIT 1;

                normalized := '{}'::jsonb;
                IF api_url_value IS NOT NULL AND jsonb_typeof(api_url_value) <> 'null' THEN
                    normalized := normalized || jsonb_build_object('api_url', api_url_value);
                END IF;
                IF pid_value IS NOT NULL AND jsonb_typeof(pid_value) <> 'null' THEN
                    normalized := normalized || jsonb_build_object('pid', pid_value);
                END IF;
                IF key_value IS NOT NULL AND jsonb_typeof(key_value) <> 'null' THEN
                    normalized := normalized || jsonb_build_object('key', key_value);
                END IF;

                -- JSONB comparison makes this idempotent and avoids an UPDATE
                -- when the object is already canonical, regardless of key
                -- ordering or insignificant JSON text formatting.
                IF stored IS DISTINCT FROM normalized THEN
                    UPDATE payment_channels
                    SET config_data = normalized::text
                    WHERE id = channel_row.id;
                END IF;
            END IF;
        EXCEPTION WHEN others THEN
            -- Keep malformed legacy data visible to the application so it can
            -- fail closed with a channel-unavailable error.
            NULL;
        END;
    END LOOP;
END $$;
