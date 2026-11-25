-- Keep every historical order number, while replacing the V21 sequence for
-- new rows with Asia/Shanghai date + eight random digits.
CREATE TABLE order_no_registry (
    order_no BIGINT CONSTRAINT order_no_registry_pkey PRIMARY KEY,
    CONSTRAINT order_no_registry_positive CHECK (order_no > 0)
);

INSERT INTO order_no_registry(order_no)
SELECT order_no FROM orders;

ALTER TABLE orders ALTER COLUMN order_no DROP DEFAULT;
DROP SEQUENCE order_no_sequence;

CREATE OR REPLACE FUNCTION generate_order_business_number()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    local_day TEXT := to_char(clock_timestamp() AT TIME ZONE 'Asia/Shanghai', 'YYYYMMDD');
    candidate BIGINT;
    reserved_number BIGINT;
BEGIN
    LOOP
        candidate := (local_day || lpad(floor(random() * 100000000)::INTEGER::TEXT, 8, '0'))::BIGINT;

        -- The registry's unique key arbitrates collisions across concurrent
        -- transactions, including collisions with numbers issued earlier.
        INSERT INTO order_no_registry(order_no)
        VALUES (candidate)
        ON CONFLICT (order_no) DO NOTHING
        RETURNING order_no INTO reserved_number;

        IF FOUND THEN
            NEW.order_no := reserved_number;
            RETURN NEW;
        END IF;
    END LOOP;
END
$$;

CREATE TRIGGER trg_orders_order_no_generate
    BEFORE INSERT ON orders
    FOR EACH ROW EXECUTE FUNCTION generate_order_business_number();
