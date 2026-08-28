-- V5 replaces the fixed customer_input_type/customer_input_value business
-- model with arbitrary, id-stable product field definitions and JSON
-- snapshots. The V4 columns remain for rollback/old-client compatibility;
-- application code after V5 reads and writes only the JSON columns.

ALTER TABLE products
    ADD COLUMN IF NOT EXISTS customer_input_fields jsonb;

UPDATE products
SET customer_input_fields = CASE UPPER(COALESCE(NULLIF(customer_input_type, ''), 'NONE'))
    WHEN 'QQ' THEN jsonb_build_array(
        jsonb_build_object('id', gen_random_uuid()::text, 'label', 'QQ号码'))
    WHEN 'PHONE' THEN jsonb_build_array(
        jsonb_build_object('id', gen_random_uuid()::text, 'label', '手机号'))
    WHEN 'QQ_OR_PHONE' THEN jsonb_build_array(
        jsonb_build_object('id', gen_random_uuid()::text, 'label', 'QQ或手机号'))
    ELSE '[]'::jsonb
END
WHERE customer_input_fields IS NULL;

ALTER TABLE products
    ALTER COLUMN customer_input_fields SET DEFAULT '[]'::jsonb,
    ALTER COLUMN customer_input_fields SET NOT NULL;

ALTER TABLE cart_items
    ADD COLUMN IF NOT EXISTS customer_inputs jsonb;

-- A legacy cart scalar can only map to the first/current field. Products
-- migrated from V3/V4 have at most one such field, so this conversion is
-- deterministic and leaves a no-field cart as an empty array.
UPDATE cart_items ci
SET customer_inputs = CASE
    WHEN NULLIF(BTRIM(ci.customer_input_value), '') IS NULL
         OR jsonb_array_length(COALESCE(p.customer_input_fields, '[]'::jsonb)) = 0
        THEN '[]'::jsonb
    ELSE jsonb_build_array(jsonb_build_object(
        'id', p.customer_input_fields->0->>'id',
        'label', p.customer_input_fields->0->>'label',
        'value', BTRIM(ci.customer_input_value)))
END
FROM products p
WHERE ci.product_id = p.id
  AND ci.customer_inputs IS NULL;

UPDATE cart_items
SET customer_inputs = '[]'::jsonb
WHERE customer_inputs IS NULL;

ALTER TABLE cart_items
    ALTER COLUMN customer_inputs SET DEFAULT '[]'::jsonb,
    ALTER COLUMN customer_inputs SET NOT NULL;

ALTER TABLE order_items
    ADD COLUMN IF NOT EXISTS customer_inputs jsonb,
    ADD COLUMN IF NOT EXISTS delivery_notified_at timestamp;

-- Keep the historical label that was visible under the old fixed enum. Each
-- legacy order item gets an independent stable snapshot id because there was
-- no product-field id to recover from the old schema.
UPDATE order_items
SET customer_inputs = CASE
    WHEN NULLIF(BTRIM(customer_input_value), '') IS NULL
         OR UPPER(COALESCE(customer_input_type, 'NONE')) = 'NONE'
        THEN '[]'::jsonb
    ELSE jsonb_build_array(jsonb_build_object(
        'id', gen_random_uuid()::text,
        'label', CASE UPPER(COALESCE(customer_input_type, ''))
            WHEN 'QQ' THEN 'QQ号码'
            WHEN 'PHONE' THEN '手机号'
            WHEN 'QQ_OR_PHONE' THEN 'QQ或手机号'
            ELSE '客户信息'
        END,
        'value', BTRIM(customer_input_value)))
END
WHERE customer_inputs IS NULL;

UPDATE order_items
SET customer_inputs = '[]'::jsonb
WHERE customer_inputs IS NULL;

ALTER TABLE order_items
    ALTER COLUMN customer_inputs SET DEFAULT '[]'::jsonb,
    ALTER COLUMN customer_inputs SET NOT NULL;

-- Replaying a pre-V5 delivered order must not send its historical card body a
-- second time. Newly delivered automatic items remain NULL and are claimed by
-- the fulfillment transaction before the after-commit email callback.
UPDATE order_items oi
SET delivery_notified_at = COALESCE(oi.delivered_at, o.delivered_at, CURRENT_TIMESTAMP)
FROM orders o
WHERE oi.order_id = o.id
  AND oi.delivery_type = 'AUTO'
  AND oi.fulfillment_status = 'DELIVERED'
  AND o.status = 'DELIVERED'
  AND oi.delivery_notified_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_cart_items_owner_product_spec
    ON cart_items(user_id, product_id, spec_id);
CREATE INDEX IF NOT EXISTS idx_cart_items_session_product_spec
    ON cart_items(session_token, product_id, spec_id);
