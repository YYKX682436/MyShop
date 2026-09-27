-- Phase 2C: reserve a local takeover state without changing existing values.
ALTER TABLE sales_channel_listings
    DROP CONSTRAINT sales_channel_listings_management_mode_check;

ALTER TABLE sales_channel_listings
    ADD CONSTRAINT sales_channel_listings_management_mode_check
    CHECK (management_mode IN ('MANAGED', 'LINKED_ONLY', 'TAKEOVER_PENDING'));
