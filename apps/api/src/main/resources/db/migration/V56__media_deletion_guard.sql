-- A committed tombstone bridges PostgreSQL and the upload filesystem. All
-- business writes take a shared transaction lock; resource deletion takes the
-- matching exclusive session lock, commits the tombstone, then removes the file.
-- This also rejects an old editor form submitted after its image was deleted.
CREATE TABLE media_deleted_files (
    filename varchar(100) PRIMARY KEY,
    deleted_at timestamptz NOT NULL DEFAULT now()
);

-- Normalize the ASCII URL escapes that can encode generated filenames, and
-- numeric HTML entities used in Markdown. Unknown/pathological encodings fail
-- closed rather than being silently treated as unreferenced.
CREATE OR REPLACE FUNCTION myshop_media_normalize(input text) RETURNS text
LANGUAGE plpgsql IMMUTABLE STRICT AS $$
DECLARE
    value text := lower(input);
    output text;
    token text;
    code integer;
    i integer;
    pass integer;
    unicode_pass integer;
BEGIN
    FOR pass IN 1..8 LOOP
        -- Decode ASCII escapes in nested JSON and remove Markdown punctuation
        -- escapes. Extra matches can only make deletion more conservative.
        FOR unicode_pass IN 1..128 LOOP
            token := substring(value from '\\u00[2-7][0-9a-f]');
            EXIT WHEN token IS NULL;
            code := get_byte(decode(right(token, 2), 'hex'), 0);
            value := replace(value, token, chr(code));
        END LOOP;
        IF substring(value from '\\u00[2-7][0-9a-f]') IS NOT NULL THEN RETURN NULL; END IF;
        value := lower(replace(value, chr(92), ''));
        value := replace(replace(value, '&period;', '.'), '&percnt;', '%');
        IF value !~ '(%[2-7][0-9a-f]|&#(?:x[0-7]?[0-9a-f]|[0-9]{1,3});)' THEN
            RETURN value;
        END IF;
        IF length(value) > 1000000 THEN RETURN NULL; END IF;
        output := '';
        i := 1;
        WHILE i <= length(value) LOOP
            token := substring(value from i for 3);
            IF token ~ '^%[2-7][0-9a-f]$' THEN
                code := get_byte(decode(substring(token from 2), 'hex'), 0);
                output := output || chr(code);
                i := i + 3;
            ELSIF substring(value from i) ~ '^&#(?:x[0-7]?[0-9a-f]|[0-9]{1,3});' THEN
                token := substring(substring(value from i) from '^&#(?:x[0-7]?[0-9a-f]|[0-9]{1,3});');
                IF substring(token from 3 for 1) = 'x' THEN
                    code := get_byte(decode(lpad(substring(token from 4 for length(token)-4), 2, '0'), 'hex'), 0);
                ELSE
                    code := substring(token from 3 for length(token)-3)::integer;
                END IF;
                IF code < 32 OR code > 126 THEN RETURN NULL; END IF;
                output := output || chr(code);
                i := i + length(token);
            ELSE
                output := output || substring(value from i for 1);
                i := i + 1;
            END IF;
        END LOOP;
        value := lower(output);
    END LOOP;
    IF value ~ '(%[2-7][0-9a-f]|&#(?:x[0-7]?[0-9a-f]|[0-9]{1,3});|&period;|&percnt;)' THEN RETURN NULL; END IF;
    RETURN value;
END $$;

CREATE OR REPLACE FUNCTION myshop_media_guard_write() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE normalized text;
BEGIN
    PERFORM pg_advisory_xact_lock_shared(724385982937::bigint);
    IF EXISTS (SELECT 1 FROM media_deleted_files) THEN
        IF TG_TABLE_NAME = 'site_configs' THEN
            IF NEW.config_key = 'custom_css' AND position(chr(92) IN coalesce(NEW.config_value, '')) > 0 THEN
                RAISE EXCEPTION 'escaped CSS image references require manual review'
                    USING ERRCODE = '23514';
            END IF;
        END IF;
        normalized := myshop_media_normalize(to_jsonb(NEW)::text);
        IF normalized IS NULL OR EXISTS (
            SELECT 1 FROM media_deleted_files WHERE position(filename IN normalized) > 0
        ) THEN
            RAISE EXCEPTION 'a referenced managed image was deleted; refresh and select an available image'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END $$;

-- Guard every existing business table, including JSON snapshots and less
-- obvious references. The deletion service refuses to run if a future table
-- does not have the guard installed.
DO $$
DECLARE business_table record;
BEGIN
    FOR business_table IN
        SELECT tablename FROM pg_tables
        WHERE schemaname = 'public' AND tablename NOT IN ('flyway_schema_history', 'media_deleted_files')
    LOOP
        EXECUTE format('CREATE TRIGGER myshop_media_guard BEFORE INSERT OR UPDATE ON public.%I FOR EACH ROW EXECUTE FUNCTION myshop_media_guard_write()', business_table.tablename);
    END LOOP;
END $$;
