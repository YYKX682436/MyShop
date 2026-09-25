-- Phase 2B: distinguish MyShop-managed listings from local-only identity links.
ALTER TABLE sales_channel_listings
    ADD COLUMN management_mode varchar(32) NOT NULL DEFAULT 'MANAGED';

ALTER TABLE sales_channel_listings
    ADD CONSTRAINT sales_channel_listings_management_mode_check
    CHECK (management_mode IN ('MANAGED', 'LINKED_ONLY'));
