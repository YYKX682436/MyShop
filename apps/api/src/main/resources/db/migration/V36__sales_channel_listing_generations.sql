-- Phase 2D-C1: separate the long-lived logical listing from provider item generations.
-- V1-V35 are immutable.  Every backfill below is exact by account and item id;
-- no title, product, or catalog fuzzy matching is permitted.

ALTER TABLE sales_channel_listings
    ADD CONSTRAINT uq_sales_channel_listings_id_account
    UNIQUE (id, sales_channel_account_id);

CREATE TABLE sales_channel_listing_generations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    sales_channel_listing_id uuid NOT NULL,
    sales_channel_account_id uuid NOT NULL,
    generation_no bigint NOT NULL,
    external_item_id varchar(128) NOT NULL,
    is_current boolean NOT NULL,
    origin varchar(32) NOT NULL,
    activated_at timestamp,
    retired_at timestamp,
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_sales_channel_listing_generations_listing_account
        FOREIGN KEY (sales_channel_listing_id, sales_channel_account_id)
        REFERENCES sales_channel_listings(id, sales_channel_account_id),
    CONSTRAINT uq_sales_channel_listing_generations_account_item
        UNIQUE (sales_channel_account_id, external_item_id),
    CONSTRAINT uq_sales_channel_listing_generations_listing_no
        UNIQUE (sales_channel_listing_id, generation_no),
    CONSTRAINT sales_channel_listing_generations_no_check
        CHECK (generation_no > 0),
    CONSTRAINT sales_channel_listing_generations_origin_check
        CHECK (origin IN ('PUBLISH', 'LINK', 'LEGACY_MIGRATION'))
);

CREATE UNIQUE INDEX uq_sales_channel_listing_generations_current
    ON sales_channel_listing_generations(sales_channel_listing_id)
    WHERE is_current = true;

CREATE UNIQUE INDEX uq_sales_channel_listing_generations_id_listing
    ON sales_channel_listing_generations(id, sales_channel_listing_id);

CREATE UNIQUE INDEX uq_sales_channel_listing_generations_id_account_listing
    ON sales_channel_listing_generations(id, sales_channel_account_id, sales_channel_listing_id);

CREATE INDEX idx_sales_channel_listing_generations_listing
    ON sales_channel_listing_generations(sales_channel_listing_id, generation_no);

CREATE INDEX idx_sales_channel_listing_generations_account_item
    ON sales_channel_listing_generations(sales_channel_account_id, external_item_id);

CREATE TABLE sales_channel_listing_generation_specs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    sales_channel_listing_generation_id uuid NOT NULL,
    product_spec_id uuid NOT NULL,
    external_sku_id varchar(128),
    provider_mapping jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_sales_channel_listing_generation_specs_generation
        FOREIGN KEY (sales_channel_listing_generation_id)
        REFERENCES sales_channel_listing_generations(id),
    CONSTRAINT fk_sales_channel_listing_generation_specs_product_spec
        FOREIGN KEY (product_spec_id) REFERENCES product_specs(id),
    CONSTRAINT uq_sales_channel_listing_generation_specs_product_spec
        UNIQUE (sales_channel_listing_generation_id, product_spec_id)
);

CREATE UNIQUE INDEX uq_sales_channel_listing_generation_specs_external_sku
    ON sales_channel_listing_generation_specs(sales_channel_listing_generation_id, external_sku_id)
    WHERE external_sku_id IS NOT NULL;

CREATE INDEX idx_sales_channel_listing_generation_specs_product_spec
    ON sales_channel_listing_generation_specs(product_spec_id);

-- One generation 1 is created for every existing listing identity.  The
-- listing's exact account is carried into the generation and checked by the
-- composite foreign key above.
INSERT INTO sales_channel_listing_generations
    (sales_channel_listing_id, sales_channel_account_id, generation_no,
     external_item_id, is_current, origin, activated_at)
SELECT listing.id, listing.sales_channel_account_id, 1,
       listing.external_item_id, true, 'LEGACY_MIGRATION', CURRENT_TIMESTAMP
FROM sales_channel_listings listing
WHERE listing.external_item_id IS NOT NULL;

INSERT INTO sales_channel_listing_generation_specs
    (sales_channel_listing_generation_id, product_spec_id, external_sku_id,
     provider_mapping, created_at, updated_at)
SELECT generation.id, legacy.product_spec_id, legacy.external_sku_id,
       legacy.provider_mapping, legacy.created_at, legacy.updated_at
FROM sales_channel_listing_specs legacy
JOIN sales_channel_listing_generations generation
  ON generation.sales_channel_listing_id = legacy.sales_channel_listing_id
 AND generation.generation_no = 1;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM sales_channel_listing_specs legacy
        LEFT JOIN sales_channel_listing_generations generation
          ON generation.sales_channel_listing_id = legacy.sales_channel_listing_id
         AND generation.generation_no = 1
        WHERE generation.id IS NULL
    ) THEN
        RAISE EXCEPTION 'V36 cannot discard a listing spec without an exact provider generation identity';
    END IF;
END
$$;

ALTER TABLE sales_channel_orders
    ADD COLUMN sales_channel_listing_generation_id uuid;

ALTER TABLE sales_channel_operations
    ADD COLUMN sales_channel_listing_generation_id uuid;

-- Orders are immutable source facts.  Resolve only the exact provider account
-- and item pair and fail if a real row cannot be resolved uniquely.
UPDATE sales_channel_orders order_row
SET sales_channel_listing_generation_id = generation.id
FROM sales_channel_listing_generations generation
WHERE order_row.sales_channel_listing_generation_id IS NULL
  AND order_row.sales_channel_account_id = generation.sales_channel_account_id
  AND order_row.external_item_id = generation.external_item_id;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM sales_channel_orders
        WHERE sales_channel_listing_generation_id IS NULL
    ) THEN
        RAISE EXCEPTION 'V36 cannot resolve one or more sales_channel_orders by exact account/item identity';
    END IF;
END
$$;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM sales_channel_orders order_row
        JOIN sales_channel_listing_generations generation
          ON generation.id = order_row.sales_channel_listing_generation_id
        WHERE order_row.sales_channel_listing_id IS NOT NULL
          AND order_row.sales_channel_listing_id <> generation.sales_channel_listing_id
    ) THEN
        RAISE EXCEPTION 'V36 found an order whose listing identity conflicts with its exact generation identity';
    END IF;
END
$$;

UPDATE sales_channel_orders order_row
SET sales_channel_listing_id = generation.sales_channel_listing_id
FROM sales_channel_listing_generations generation
WHERE order_row.sales_channel_listing_generation_id = generation.id
  AND order_row.sales_channel_listing_id IS NULL;

ALTER TABLE sales_channel_orders
    ADD CONSTRAINT fk_sales_channel_orders_generation
        FOREIGN KEY (sales_channel_listing_generation_id)
        REFERENCES sales_channel_listing_generations(id),
    ADD CONSTRAINT fk_sales_channel_orders_generation_scope
        FOREIGN KEY (sales_channel_listing_generation_id, sales_channel_account_id,
                     sales_channel_listing_id)
        REFERENCES sales_channel_listing_generations(id, sales_channel_account_id,
                                                      sales_channel_listing_id);

-- Existing-item operations are assigned by their frozen provider identity.
-- RECONCILE inherits the generation of its target operation.  PUBLISH remains
-- nullable unless its successful result explicitly names an existing item.
UPDATE sales_channel_operations operation_row
SET sales_channel_listing_generation_id = generation.id
FROM sales_channel_listing_generations generation
JOIN sales_channel_listings listing
  ON listing.id = generation.sales_channel_listing_id
WHERE operation_row.sales_channel_listing_generation_id IS NULL
  AND operation_row.operation_type IN ('SYNC', 'OFFLINE', 'DELETE')
  AND operation_row.sales_channel_listing_id = generation.sales_channel_listing_id
  AND listing.external_item_id = generation.external_item_id;

UPDATE sales_channel_operations reconcile_row
SET sales_channel_listing_generation_id = target_row.sales_channel_listing_generation_id
FROM sales_channel_operations target_row
WHERE reconcile_row.sales_channel_listing_generation_id IS NULL
  AND reconcile_row.operation_type = 'RECONCILE'
  AND target_row.id::text = lower(reconcile_row.payload ->> 'target_operation_id')
  AND target_row.sales_channel_listing_generation_id IS NOT NULL;

UPDATE sales_channel_operations publish_row
SET sales_channel_listing_generation_id = generation.id
FROM sales_channel_listing_generations generation
WHERE publish_row.sales_channel_listing_generation_id IS NULL
  AND publish_row.operation_type = 'PUBLISH'
  AND publish_row.status = 'SUCCEEDED'
  AND COALESCE(publish_row.result ->> 'item_id', publish_row.result ->> 'external_item_id')
      = generation.external_item_id
  AND publish_row.sales_channel_listing_id = generation.sales_channel_listing_id;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM sales_channel_operations
        WHERE operation_type IN ('SYNC', 'OFFLINE', 'DELETE')
          AND sales_channel_listing_generation_id IS NULL
    ) THEN
        RAISE EXCEPTION 'V36 cannot resolve an existing-item operation to an exact generation identity';
    END IF;
END
$$;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM sales_channel_operations reconcile_row
        WHERE reconcile_row.operation_type = 'RECONCILE'
          AND (
              reconcile_row.payload ->> 'target_operation_id' IS NULL
              OR reconcile_row.payload ->> 'target_operation_id' !~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
              OR NOT EXISTS (
                  SELECT 1
                  FROM sales_channel_operations target_row
                  WHERE target_row.id::text = lower(reconcile_row.payload ->> 'target_operation_id')
              )
          )
    ) THEN
        RAISE EXCEPTION 'V36 cannot resolve a reconciliation target operation identity';
    END IF;
END
$$;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM sales_channel_operations reconcile_row
        JOIN sales_channel_operations target_row
          ON target_row.id::text = lower(reconcile_row.payload ->> 'target_operation_id')
        WHERE reconcile_row.operation_type = 'RECONCILE'
          AND reconcile_row.sales_channel_listing_generation_id IS DISTINCT FROM target_row.sales_channel_listing_generation_id
    ) THEN
        RAISE EXCEPTION 'V36 reconciliation generation identity does not match its target operation';
    END IF;
END
$$;

ALTER TABLE sales_channel_operations
    ADD CONSTRAINT fk_sales_channel_operations_generation
        FOREIGN KEY (sales_channel_listing_generation_id)
        REFERENCES sales_channel_listing_generations(id),
    ADD CONSTRAINT fk_sales_channel_operations_generation_scope
        FOREIGN KEY (sales_channel_listing_generation_id, sales_channel_listing_id)
        REFERENCES sales_channel_listing_generations(id, sales_channel_listing_id),
    ADD CONSTRAINT sales_channel_operations_generation_required_check
        CHECK (operation_type IN ('PUBLISH', 'RECONCILE')
               OR sales_channel_listing_generation_id IS NOT NULL);

CREATE INDEX idx_sales_channel_orders_generation
    ON sales_channel_orders(sales_channel_listing_generation_id);

CREATE INDEX idx_sales_channel_operations_generation
    ON sales_channel_operations(sales_channel_listing_generation_id, created_at);

-- The old listing-level provider identity and mapping table are no longer a
-- domain source.  All facts now resolve through generations.
DROP INDEX IF EXISTS uq_sales_channel_listings_account_external_item;
DROP INDEX IF EXISTS idx_sales_channel_listings_external_item;
ALTER TABLE sales_channel_listings DROP COLUMN external_item_id;
DROP TABLE sales_channel_listing_specs;
