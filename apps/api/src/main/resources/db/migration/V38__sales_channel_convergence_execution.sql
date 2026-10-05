-- S2.3: MyShop stores desired intent and projects Agent-owned listing identity.
-- V30-V37 and all historical operation rows remain immutable.

ALTER TABLE sales_channel_operations
    DROP CONSTRAINT sales_channel_operations_type_check,
    ADD CONSTRAINT sales_channel_operations_type_check
        CHECK (operation_type IN ('PUBLISH', 'REPUBLISH', 'SYNC', 'OFFLINE', 'DELETE', 'RECONCILE', 'ENSURE_LISTING'));

ALTER TABLE sales_channel_operations
    DROP CONSTRAINT sales_channel_operations_generation_semantics_check,
    ADD CONSTRAINT sales_channel_operations_generation_semantics_check
        CHECK (
            (operation_type = 'PUBLISH' AND target_generation_id IS NULL)
            OR (operation_type = 'REPUBLISH' AND target_generation_id IS NOT NULL)
            OR (operation_type IN ('SYNC', 'OFFLINE', 'DELETE') AND target_generation_id IS NOT NULL)
            OR operation_type = 'RECONCILE'
            OR (operation_type = 'ENSURE_LISTING'
                AND target_generation_id IS NULL AND result_generation_id IS NULL)
        );

ALTER TABLE sales_channel_listings
    ADD COLUMN convergence_adopted_external_item_id varchar(128),
    ADD COLUMN convergence_adopted_at timestamp,
    DROP CONSTRAINT sales_channel_listings_status_check,
    ADD CONSTRAINT sales_channel_listings_status_check
        CHECK (status IN ('DRAFT', 'APPLYING', 'PUBLISHING', 'REPUBLISHING', 'REPLACEMENT_PENDING',
                          'ONLINE', 'SYNCING', 'OFFLINING', 'OFFLINE', 'ERROR', 'UNKNOWN'));

ALTER TABLE sales_channel_listing_generations
    DROP CONSTRAINT sales_channel_listing_generations_origin_check,
    ADD CONSTRAINT sales_channel_listing_generations_origin_check
        CHECK (origin IN ('PUBLISH', 'LINK', 'LEGACY_MIGRATION', 'REPUBLISH', 'CONVERGENCE'));

COMMENT ON COLUMN sales_channel_listings.convergence_adopted_external_item_id IS
    'Crash-safe projection that MyShop completed Agent identity adoption; not provider truth';
COMMENT ON COLUMN sales_channel_listings.convergence_adopted_at IS
    'Timestamp for the last exact Agent identity adoption projection';
