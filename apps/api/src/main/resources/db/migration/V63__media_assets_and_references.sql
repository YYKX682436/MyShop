-- File discovery and business-reference backfill run outside this migration.
-- These tables intentionally describe public media only; private attachments
-- retain their own storage and access controls.
CREATE TABLE media_assets (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    storage_path text UNIQUE,
    display_path text,
    thumbnail_path text,
    clear_path text,
    original_filename varchar(512) NOT NULL,
    media_type varchar(8) NOT NULL CHECK (media_type IN ('IMAGE', 'VIDEO')),
    mime_type varchar(128),
    size_bytes bigint NOT NULL DEFAULT 0 CHECK (size_bytes >= 0),
    original_size_bytes bigint CHECK (original_size_bytes >= 0),
    thumbnail_size_bytes bigint CHECK (thumbnail_size_bytes >= 0),
    clear_size_bytes bigint CHECK (clear_size_bytes >= 0),
    width integer CHECK (width > 0),
    height integer CHECK (height > 0),
    duration_ms bigint CHECK (duration_ms >= 0),
    content_hash varchar(64),
    source_hash varchar(64),
    scope varchar(12) NOT NULL DEFAULT 'SHARED' CHECK (scope IN ('SHARED', 'AVATAR')),
    uploader_id uuid,
    owner_user_id uuid,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processing_status varchar(16) NOT NULL DEFAULT 'READY'
        CHECK (processing_status IN ('UPLOADING', 'PROCESSING', 'READY', 'FAILED', 'REUSED')),
    upload_uri text UNIQUE,
    declared_size_bytes bigint CHECK (declared_size_bytes >= 0),
    processing_error text,
    processing_reason varchar(64),
    deleted_at timestamptz,
    cleanup_error text,
    upload_cleanup_error text,
    reused_asset_id uuid REFERENCES media_assets(id),
    CHECK (scope <> 'SHARED' OR owner_user_id IS NULL)
);

CREATE INDEX idx_media_assets_created ON media_assets(created_at DESC, id DESC);
CREATE INDEX idx_media_assets_size ON media_assets(size_bytes DESC, id DESC);
CREATE INDEX idx_media_assets_type_created ON media_assets(media_type, created_at DESC, id DESC);
CREATE INDEX idx_media_assets_hash_scope ON media_assets(content_hash, scope, owner_user_id)
    WHERE deleted_at IS NULL AND content_hash IS NOT NULL;
CREATE INDEX idx_media_assets_video_source ON media_assets(source_hash) WHERE media_type='VIDEO' AND deleted_at IS NULL AND source_hash IS NOT NULL;
CREATE INDEX idx_media_assets_legacy_tombstone ON media_assets(lower(storage_path))
    WHERE deleted_at IS NOT NULL AND position('/' in storage_path)=0;
CREATE INDEX idx_media_assets_display_path ON media_assets(display_path) WHERE display_path IS NOT NULL;
CREATE INDEX idx_media_assets_thumbnail_path ON media_assets(thumbnail_path) WHERE thumbnail_path IS NOT NULL;
CREATE INDEX idx_media_assets_clear_path ON media_assets(clear_path) WHERE clear_path IS NOT NULL;

CREATE TABLE media_references (
    asset_id uuid NOT NULL REFERENCES media_assets(id),
    owner_type varchar(24) NOT NULL CHECK (owner_type IN ('PRODUCT', 'AVATAR', 'SITE_CONFIG', 'SUBSITE', 'ANNOUNCEMENT')),
    owner_id uuid NOT NULL,
    field varchar(32) NOT NULL,
    PRIMARY KEY (asset_id, owner_type, owner_id, field)
);
CREATE INDEX idx_media_references_owner ON media_references(owner_type, owner_id, field, asset_id);

-- Preserve old tombstones before dropping the global guard. A later upload of
-- the same bytes gets a new path; it never revives this historical address.
INSERT INTO media_assets(storage_path, original_filename, media_type, mime_type, deleted_at)
SELECT lower(filename), filename, 'IMAGE', 'application/octet-stream', deleted_at
FROM media_deleted_files ON CONFLICT (storage_path) DO NOTHING;
