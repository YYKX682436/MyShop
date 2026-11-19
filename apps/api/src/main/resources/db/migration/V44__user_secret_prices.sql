CREATE TABLE user_secret_prices (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    product_id uuid NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    spec_id uuid,
    price numeric(10,2) NOT NULL CHECK (price > 0),
    CONSTRAINT user_secret_prices_spec_fk FOREIGN KEY (product_id, spec_id)
        REFERENCES product_specs(product_id, id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX uq_user_secret_price_product ON user_secret_prices(user_id, product_id) WHERE spec_id IS NULL;
CREATE UNIQUE INDEX uq_user_secret_price_spec ON user_secret_prices(user_id, product_id, spec_id) WHERE spec_id IS NOT NULL;
CREATE INDEX idx_user_secret_prices_product ON user_secret_prices(product_id);

ALTER TABLE order_items DROP CONSTRAINT order_items_pricing_source_check;
ALTER TABLE order_items ADD CONSTRAINT order_items_pricing_source_check
    CHECK (pricing_source IN ('RETAIL', 'LEVEL_MARKUP', 'LEVEL_OVERRIDE', 'SALES_CHANNEL', 'USER_SECRET'));

-- Lock the product just like the admin services: price changes and secret-price
-- writes must not race each other and leave an invalid retail cap or target.
CREATE FUNCTION validate_user_secret_price() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    product_mode boolean;
    product_deleted integer;
    retail numeric(10,2);
BEGIN
    SELECT spec_enabled, is_deleted, retail_price INTO product_mode, product_deleted, retail
      FROM products WHERE id = NEW.product_id FOR UPDATE;
    IF NOT FOUND OR product_deleted <> 0 THEN
        RAISE EXCEPTION 'Secret price product does not exist';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM users WHERE id = NEW.user_id AND role = 'USER') THEN
        RAISE EXCEPTION 'Secret price user does not exist';
    END IF;
    IF product_mode <> (NEW.spec_id IS NOT NULL) THEN
        RAISE EXCEPTION 'Secret price target does not match specification mode';
    END IF;
    IF NEW.spec_id IS NOT NULL THEN
        SELECT retail_price INTO retail FROM product_specs
          WHERE id = NEW.spec_id AND product_id = NEW.product_id AND is_deleted = 0;
        IF NOT FOUND THEN RAISE EXCEPTION 'Secret price spec does not exist'; END IF;
    END IF;
    IF NEW.price > retail THEN RAISE EXCEPTION 'Secret price exceeds retail price'; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_validate_user_secret_price BEFORE INSERT OR UPDATE ON user_secret_prices
    FOR EACH ROW EXECUTE FUNCTION validate_user_secret_price();

CREATE FUNCTION maintain_product_secret_prices() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.is_deleted <> 0 THEN
        DELETE FROM user_secret_prices WHERE product_id = NEW.id;
    ELSIF NEW.spec_enabled IS DISTINCT FROM OLD.spec_enabled THEN
        DELETE FROM user_secret_prices WHERE product_id = NEW.id;
    END IF;
    IF NOT NEW.spec_enabled AND EXISTS (
        SELECT 1 FROM user_secret_prices WHERE product_id = NEW.id AND spec_id IS NULL AND price > NEW.retail_price
    ) THEN RAISE EXCEPTION 'Retail price is below an existing user secret price'; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_maintain_product_secret_prices AFTER UPDATE OF retail_price, spec_enabled, is_deleted ON products
    FOR EACH ROW EXECUTE FUNCTION maintain_product_secret_prices();

CREATE FUNCTION maintain_spec_secret_prices() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.is_deleted <> 0 THEN
        DELETE FROM user_secret_prices WHERE product_id = NEW.product_id AND spec_id = NEW.id;
    ELSIF EXISTS (SELECT 1 FROM user_secret_prices WHERE product_id = NEW.product_id
                 AND spec_id = NEW.id AND price > NEW.retail_price) THEN
        RAISE EXCEPTION 'Retail price is below an existing user secret price';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_maintain_spec_secret_prices AFTER UPDATE OF retail_price, is_deleted ON product_specs
    FOR EACH ROW EXECUTE FUNCTION maintain_spec_secret_prices();
