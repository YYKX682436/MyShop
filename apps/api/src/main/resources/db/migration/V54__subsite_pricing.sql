-- A NULL price means the site inherits the current main-site retail price.
CREATE TABLE subsite_product_prices (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    subsite_id uuid NOT NULL REFERENCES subsites(id),
    product_id uuid NOT NULL REFERENCES products(id),
    spec_id uuid,
    user_level_id uuid REFERENCES user_levels(id),
    price numeric(10,2) NOT NULL CHECK (price > 0),
    FOREIGN KEY (product_id, spec_id) REFERENCES product_specs(product_id, id)
);
CREATE UNIQUE INDEX uq_subsite_product_price_root ON subsite_product_prices(subsite_id, product_id) WHERE spec_id IS NULL AND user_level_id IS NULL;
CREATE UNIQUE INDEX uq_subsite_product_price_spec ON subsite_product_prices(subsite_id, product_id, spec_id) WHERE spec_id IS NOT NULL AND user_level_id IS NULL;
CREATE UNIQUE INDEX uq_subsite_product_price_level ON subsite_product_prices(subsite_id, product_id, user_level_id) WHERE spec_id IS NULL AND user_level_id IS NOT NULL;
CREATE UNIQUE INDEX uq_subsite_spec_price_level ON subsite_product_prices(subsite_id, product_id, spec_id, user_level_id) WHERE spec_id IS NOT NULL AND user_level_id IS NOT NULL;
CREATE TABLE subsite_level_markups (
    subsite_id uuid NOT NULL REFERENCES subsites(id),
    user_level_id uuid NOT NULL REFERENCES user_levels(id),
    markup_rate numeric(9,4) NOT NULL CHECK (markup_rate >= 0),
    PRIMARY KEY (subsite_id, user_level_id)
);
CREATE TABLE subsite_hidden_products (
    subsite_id uuid NOT NULL REFERENCES subsites(id),
    product_id uuid NOT NULL REFERENCES products(id),
    PRIMARY KEY (subsite_id, product_id)
);
ALTER TABLE order_items DROP CONSTRAINT order_items_pricing_source_check;
ALTER TABLE order_items ADD CONSTRAINT order_items_pricing_source_check
    CHECK (pricing_source IN ('RETAIL', 'LEVEL_MARKUP', 'LEVEL_OVERRIDE', 'SALES_CHANNEL', 'USER_SECRET', 'SUBSITE'));
