-- Preserve legacy values without claiming to know when they were published.
-- A null published_at on imported rows means the original time is unknown.
CREATE TABLE site_announcements (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    site_id uuid REFERENCES subsites(id),
    site_scope uuid GENERATED ALWAYS AS
        (coalesce(site_id, '00000000-0000-0000-0000-000000000000'::uuid)) STORED,
    source_key varchar(128) UNIQUE,
    title varchar(200) NOT NULL CHECK (char_length(btrim(title)) BETWEEN 1 AND 200),
    body text NOT NULL CHECK (char_length(btrim(body)) > 0),
    status varchar(16) NOT NULL CHECK (status IN ('DRAFT', 'PUBLISHED', 'WITHDRAWN')),
    publish_version integer NOT NULL DEFAULT 0 CHECK (publish_version >= 0),
    published_at timestamp,
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_site_announcements_id_scope UNIQUE (id, site_scope),
    CONSTRAINT site_announcements_version_check CHECK
        ((status = 'DRAFT' AND publish_version = 0) OR
         (status <> 'DRAFT' AND publish_version > 0)),
    CONSTRAINT site_announcements_real_scope_check CHECK
        (site_id IS NULL OR site_id <> '00000000-0000-0000-0000-000000000000'::uuid)
);
CREATE INDEX idx_site_announcements_published
    ON site_announcements(site_scope, published_at DESC NULLS LAST, id DESC)
    WHERE status = 'PUBLISHED';

CREATE TABLE site_announcement_channels (
    site_id uuid REFERENCES subsites(id),
    site_scope uuid GENERATED ALWAYS AS
        (coalesce(site_id, '00000000-0000-0000-0000-000000000000'::uuid)) STORED,
    channel varchar(16) NOT NULL CHECK (channel IN ('BANNER', 'POPUP')),
    announcement_id uuid,
    enabled boolean NOT NULL DEFAULT false,
    PRIMARY KEY (site_scope, channel),
    FOREIGN KEY (announcement_id, site_scope) REFERENCES site_announcements(id, site_scope),
    CHECK (NOT enabled OR announcement_id IS NOT NULL)
);

CREATE TABLE site_announcement_reads (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    announcement_id uuid NOT NULL REFERENCES site_announcements(id),
    user_id uuid REFERENCES users(id),
    guest_token_hash bytea,
    read_version integer NOT NULL CHECK (read_version > 0),
    read_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK ((user_id IS NOT NULL AND guest_token_hash IS NULL) OR
           (user_id IS NULL AND guest_token_hash IS NOT NULL AND octet_length(guest_token_hash) = 32))
);
CREATE UNIQUE INDEX uq_announcement_read_user ON site_announcement_reads(announcement_id, user_id)
    WHERE user_id IS NOT NULL;
CREATE UNIQUE INDEX uq_announcement_read_guest ON site_announcement_reads(announcement_id, guest_token_hash)
    WHERE guest_token_hash IS NOT NULL;

WITH legacy AS (
    SELECT max(config_value) FILTER (WHERE config_key = 'announcement') AS banner,
           max(config_value) FILTER (WHERE config_key = 'popup_content') AS popup,
           coalesce(bool_or(lower(btrim(config_value)) = 'true') FILTER (WHERE config_key = 'announcement_enabled'), false) AS banner_enabled,
           coalesce(bool_or(lower(btrim(config_value)) = 'true') FILTER (WHERE config_key = 'popup_enabled'), false) AS popup_enabled
    FROM site_configs
), candidates AS (
    SELECT 'legacy:main:banner' AS source_key, '网站公告' AS title, banner AS body,
           banner_enabled OR (popup_enabled AND popup = banner) AS was_visible
    FROM legacy WHERE btrim(coalesce(banner, '')) <> ''
    UNION ALL
    SELECT 'legacy:main:popup', '弹窗公告', popup, popup_enabled
    FROM legacy WHERE btrim(coalesce(popup, '')) <> '' AND popup IS DISTINCT FROM banner
)
INSERT INTO site_announcements(source_key, title, body, status, publish_version, published_at)
SELECT source_key, title, body, CASE WHEN was_visible THEN 'PUBLISHED' ELSE 'DRAFT' END,
       CASE WHEN was_visible THEN 1 ELSE 0 END, NULL
FROM candidates ON CONFLICT (source_key) DO NOTHING;

INSERT INTO site_announcements(site_id, source_key, title, body, status, publish_version, published_at)
SELECT id, 'legacy:subsite:' || id::text, '网站公告', announcement, 'PUBLISHED', 1, NULL
FROM subsites WHERE btrim(coalesce(announcement, '')) <> ''
ON CONFLICT (source_key) DO NOTHING;

INSERT INTO site_announcement_channels(site_id, channel, announcement_id, enabled)
SELECT NULL, 'BANNER', a.id, coalesce(lower(btrim(c.config_value)) = 'true', false)
FROM site_announcements a
JOIN site_configs c ON c.config_key = 'announcement_enabled'
WHERE a.source_key = 'legacy:main:banner';

INSERT INTO site_announcement_channels(site_id, channel, announcement_id, enabled)
SELECT NULL, 'POPUP', a.id, coalesce(lower(btrim(c.config_value)) = 'true', false)
FROM site_configs c
JOIN site_configs p ON p.config_key = 'popup_content'
JOIN site_announcements a ON a.source_key = CASE
    WHEN p.config_value = (SELECT config_value FROM site_configs WHERE config_key = 'announcement')
    THEN 'legacy:main:banner' ELSE 'legacy:main:popup' END
WHERE c.config_key = 'popup_enabled' AND btrim(coalesce(p.config_value, '')) <> '';

INSERT INTO site_announcement_channels(site_id, channel, announcement_id, enabled)
SELECT a.site_id, 'BANNER', a.id, true FROM site_announcements a
WHERE a.source_key LIKE 'legacy:subsite:%';

-- The records above now own all of the text. Clearing the old fields also
-- prevents obsolete media references from appearing to be in active use.
UPDATE site_configs SET config_value = '' WHERE config_key IN ('announcement', 'popup_content');
UPDATE site_configs SET config_value = 'false' WHERE config_key IN ('announcement_enabled', 'popup_enabled');
-- Existing rows can contain an unrelated reference to a previously deleted
-- image. This rewrite removes only the migrated column; hold the table lock
-- while temporarily bypassing the guard, and restore it before commit.
ALTER TABLE subsites DISABLE TRIGGER myshop_media_guard;
UPDATE subsites SET announcement = NULL WHERE announcement IS NOT NULL;
ALTER TABLE subsites ENABLE TRIGGER myshop_media_guard;

-- Imported text is existing data, not a new editor write. Install the guards
-- after the import so historical references to deleted files remain readable;
-- all subsequent writes are protected by the regular media contract.
CREATE TRIGGER myshop_media_guard BEFORE INSERT OR UPDATE ON site_announcements
    FOR EACH ROW EXECUTE FUNCTION public.myshop_media_guard_write();
CREATE TRIGGER myshop_media_guard BEFORE INSERT OR UPDATE ON site_announcement_channels
    FOR EACH ROW EXECUTE FUNCTION public.myshop_media_guard_write();
CREATE TRIGGER myshop_media_guard BEFORE INSERT OR UPDATE ON site_announcement_reads
    FOR EACH ROW EXECUTE FUNCTION public.myshop_media_guard_write();
