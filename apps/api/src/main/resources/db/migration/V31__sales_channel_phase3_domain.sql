-- Phase 3 listing intent, desired snapshot fingerprint, and external identity integrity.
ALTER TABLE sales_channel_listings
    ADD COLUMN is_enabled boolean NOT NULL DEFAULT false,
    ADD COLUMN desired_fingerprint varchar(64);

CREATE UNIQUE INDEX uq_sales_channel_listings_account_external_item
    ON sales_channel_listings(sales_channel_account_id, external_item_id)
    WHERE external_item_id IS NOT NULL;

ALTER TABLE sales_channel_listings
    DROP CONSTRAINT sales_channel_listings_status_check;

ALTER TABLE sales_channel_listings
    ADD CONSTRAINT sales_channel_listings_status_check
    CHECK (status IN ('DRAFT', 'PUBLISHING', 'ONLINE', 'SYNCING', 'OFFLINING', 'OFFLINE',
                      'ERROR', 'UNKNOWN', 'RELIST_REQUIRED'));
