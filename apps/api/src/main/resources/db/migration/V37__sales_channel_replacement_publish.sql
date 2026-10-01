-- Phase 2D-C2A: provider generations have an explicit candidate/current/
-- retired lifecycle.  V30-V36 remain immutable.

ALTER TABLE sales_channel_listing_generations
    ADD COLUMN generation_state varchar(16),
    ADD COLUMN replaces_generation_id uuid;

UPDATE sales_channel_listing_generations
   SET generation_state = CASE
       WHEN is_current THEN 'CURRENT'
       WHEN retired_at IS NOT NULL THEN 'RETIRED'
       ELSE NULL
   END;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM sales_channel_listing_generations
        WHERE generation_state IS NULL
    ) THEN
        RAISE EXCEPTION 'V37 cannot infer lifecycle state for a non-current, non-retired generation';
    END IF;
END $$;

ALTER TABLE sales_channel_listing_generations
    ALTER COLUMN generation_state SET NOT NULL,
    ADD CONSTRAINT sales_channel_listing_generations_state_check
    CHECK (
        (generation_state = 'CANDIDATE' AND activated_at IS NULL AND retired_at IS NULL)
        OR (generation_state = 'CURRENT' AND activated_at IS NOT NULL AND retired_at IS NULL)
        OR (generation_state = 'RETIRED' AND activated_at IS NOT NULL AND retired_at IS NOT NULL)
    ),
    ADD CONSTRAINT sales_channel_listing_generations_state_value_check
    CHECK (generation_state IN ('CANDIDATE', 'CURRENT', 'RETIRED')),
    ADD CONSTRAINT sales_channel_listing_generations_not_self_replacement
    CHECK (replaces_generation_id IS NULL OR replaces_generation_id <> id),
    ADD CONSTRAINT fk_sales_channel_listing_generations_replaces
    FOREIGN KEY (replaces_generation_id, sales_channel_listing_id)
    REFERENCES sales_channel_listing_generations(id, sales_channel_listing_id);

ALTER TABLE sales_channel_listing_generations
    DROP CONSTRAINT sales_channel_listing_generations_origin_check,
    ADD CONSTRAINT sales_channel_listing_generations_origin_check
    CHECK (origin IN ('PUBLISH', 'LINK', 'LEGACY_MIGRATION', 'REPUBLISH'));

DROP INDEX IF EXISTS uq_sales_channel_listing_generations_current;
CREATE UNIQUE INDEX uq_sales_channel_listing_generations_current_state
    ON sales_channel_listing_generations(sales_channel_listing_id)
    WHERE generation_state = 'CURRENT';
CREATE UNIQUE INDEX uq_sales_channel_listing_generations_candidate_state
    ON sales_channel_listing_generations(sales_channel_listing_id)
    WHERE generation_state = 'CANDIDATE';
CREATE UNIQUE INDEX uq_sales_channel_listing_generations_successor_source
    ON sales_channel_listing_generations(replaces_generation_id)
    WHERE replaces_generation_id IS NOT NULL;

ALTER TABLE sales_channel_listing_generations
    DROP COLUMN is_current;

-- Split the C1 field into its two explicit meanings.  Existing-item
-- operations retain the frozen target; successful historical PUBLISH rows
-- carry their created generation as a result instead.
ALTER TABLE sales_channel_operations
    DROP CONSTRAINT IF EXISTS fk_sales_channel_operations_generation_scope,
    DROP CONSTRAINT IF EXISTS fk_sales_channel_operations_generation,
    DROP CONSTRAINT IF EXISTS sales_channel_operations_generation_required_check;
DROP INDEX IF EXISTS idx_sales_channel_operations_generation;
ALTER TABLE sales_channel_operations
    RENAME COLUMN sales_channel_listing_generation_id TO target_generation_id;
ALTER TABLE sales_channel_operations
    ADD COLUMN result_generation_id uuid;

UPDATE sales_channel_operations
   SET result_generation_id = target_generation_id,
       target_generation_id = NULL
 WHERE operation_type = 'PUBLISH'
   AND status = 'SUCCEEDED'
   AND target_generation_id IS NOT NULL
   AND COALESCE(result ->> 'item_id', result ->> 'external_item_id') = (
       SELECT generation.external_item_id
       FROM sales_channel_listing_generations generation
       WHERE generation.id = sales_channel_operations.target_generation_id
   );

UPDATE sales_channel_operations
   SET target_generation_id = NULL
 WHERE operation_type = 'PUBLISH';

ALTER TABLE sales_channel_operations
    ADD CONSTRAINT fk_sales_channel_operations_target_generation
        FOREIGN KEY (target_generation_id, sales_channel_listing_id)
        REFERENCES sales_channel_listing_generations(id, sales_channel_listing_id),
    ADD CONSTRAINT fk_sales_channel_operations_result_generation
        FOREIGN KEY (result_generation_id, sales_channel_listing_id)
        REFERENCES sales_channel_listing_generations(id, sales_channel_listing_id),
    ADD CONSTRAINT sales_channel_operations_generation_semantics_check
        CHECK (
            (operation_type = 'PUBLISH' AND target_generation_id IS NULL)
            OR (operation_type = 'REPUBLISH' AND target_generation_id IS NOT NULL)
            OR (operation_type IN ('SYNC', 'OFFLINE', 'DELETE') AND target_generation_id IS NOT NULL)
            OR operation_type = 'RECONCILE'
        ),
    ADD CONSTRAINT sales_channel_operations_result_semantics_check
        CHECK (result_generation_id IS NULL OR operation_type IN ('PUBLISH', 'REPUBLISH'));

CREATE INDEX idx_sales_channel_operations_target_generation
    ON sales_channel_operations(target_generation_id, created_at);
CREATE INDEX idx_sales_channel_operations_result_generation
    ON sales_channel_operations(result_generation_id, created_at);

ALTER TABLE sales_channel_operations
    DROP CONSTRAINT sales_channel_operations_type_check,
    ADD CONSTRAINT sales_channel_operations_type_check
    CHECK (operation_type IN ('PUBLISH', 'REPUBLISH', 'SYNC', 'OFFLINE', 'DELETE', 'RECONCILE'));

UPDATE sales_channel_listings
   SET status = CASE
       WHEN status = 'RELIST_REQUIRED' AND desired_state = 'OFFLINE' THEN 'OFFLINE'
       WHEN status = 'RELIST_REQUIRED' AND desired_state = 'ONLINE' THEN 'ERROR'
       ELSE status
   END,
       last_error_code = CASE
       WHEN status = 'RELIST_REQUIRED' AND desired_state = 'ONLINE' THEN 'PROVIDER_STATE_REQUIRED'
       WHEN status = 'RELIST_REQUIRED' AND desired_state = 'OFFLINE' THEN NULL
       ELSE last_error_code
   END,
       last_error_message = CASE
       WHEN status = 'RELIST_REQUIRED' AND desired_state = 'ONLINE' THEN 'A replacement provider generation is required before enabling this listing'
       WHEN status = 'RELIST_REQUIRED' AND desired_state = 'OFFLINE' THEN NULL
       ELSE last_error_message
   END
 WHERE status = 'RELIST_REQUIRED';

ALTER TABLE sales_channel_listings
    DROP CONSTRAINT sales_channel_listings_status_check,
    ADD CONSTRAINT sales_channel_listings_status_check
    CHECK (status IN ('DRAFT', 'PUBLISHING', 'REPUBLISHING', 'REPLACEMENT_PENDING',
                      'ONLINE', 'SYNCING', 'OFFLINING', 'OFFLINE', 'ERROR', 'UNKNOWN'));

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM sales_channel_listings WHERE status = 'RELIST_REQUIRED') THEN
        RAISE EXCEPTION 'V37 left RELIST_REQUIRED rows behind';
    END IF;
END $$;

COMMENT ON COLUMN sales_channel_listing_generations.generation_state IS
    'CANDIDATE before activation, CURRENT active provider item, RETIRED historical item';
COMMENT ON COLUMN sales_channel_operations.target_generation_id IS
    'Frozen provider source/target identity for an existing-item operation';
COMMENT ON COLUMN sales_channel_operations.result_generation_id IS
    'Provider generation created by a successful PUBLISH or REPUBLISH';
