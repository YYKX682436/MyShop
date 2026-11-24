-- Remove only the legacy media trigger, leaving order snapshot and all other
-- database constraints untouched. The application indexes legacy media before
-- it starts serving traffic.
DO $$
DECLARE guarded record;
BEGIN
    IF obj_description('public.media_references'::regclass, 'pg_class') IS DISTINCT FROM 'myshop-media-index-complete-v1' THEN
        RAISE EXCEPTION 'media index has not completed; run the staged application migration';
    END IF;
    FOR guarded IN
        SELECT n.nspname, c.relname, t.tgname
        FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid
        JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = 'public' AND NOT t.tgisinternal
          AND t.tgname = 'myshop_media_guard'
          AND t.tgfoid = to_regprocedure('public.myshop_media_guard_write()')
    LOOP
        EXECUTE format('DROP TRIGGER %I ON %I.%I', guarded.tgname, guarded.nspname, guarded.relname);
    END LOOP;
END $$;

DROP FUNCTION public.myshop_media_guard_write();
DROP FUNCTION public.myshop_media_normalize(text);
DROP TABLE public.media_deleted_files;
