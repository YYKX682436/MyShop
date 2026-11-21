-- Existing historical fields may retain a deleted URL. Check only columns that
-- are newly written, so an unrelated order update does not rewrite a snapshot.
CREATE OR REPLACE FUNCTION myshop_media_guard_write() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    field record;
    previous jsonb;
    normalized text;
BEGIN
    PERFORM pg_advisory_xact_lock_shared(724385982937::bigint);
    IF NOT EXISTS (SELECT 1 FROM media_deleted_files) THEN RETURN NEW; END IF;
    previous := CASE WHEN TG_OP = 'UPDATE' THEN to_jsonb(OLD) ELSE '{}'::jsonb END;
    FOR field IN SELECT key, value FROM jsonb_each(to_jsonb(NEW)) LOOP
        IF TG_OP = 'UPDATE' AND previous -> field.key IS NOT DISTINCT FROM field.value THEN
            CONTINUE;
        END IF;
        IF TG_TABLE_NAME = 'site_configs' THEN
            IF NEW.config_key = 'custom_css' AND field.key = 'config_value'
                    AND position(chr(92) IN coalesce(NEW.config_value, '')) > 0 THEN
                RAISE EXCEPTION 'escaped CSS image references require manual review' USING ERRCODE = '23514';
            END IF;
        END IF;
        normalized := myshop_media_normalize(field.value::text);
        IF normalized IS NULL OR EXISTS (
            SELECT 1 FROM media_deleted_files WHERE position(filename IN normalized) > 0
        ) THEN
            RAISE EXCEPTION 'a referenced managed image was deleted; refresh and select an available image'
                USING ERRCODE = '23514';
        END IF;
    END LOOP;
    RETURN NEW;
END $$;
